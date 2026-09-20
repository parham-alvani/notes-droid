package me.parham1995.notes.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.describeChain
import me.parham1995.notes.sync.Author
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.Rename
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.TextEdit
import me.parham1995.notes.sync.VaultEntry
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSink
import me.parham1995.notes.sync.VaultSync
import me.parham1995.notes.sync.VaultWriter
import me.parham1995.notes.sync.WriteOutcome
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PublicKey

/**
 * Syncs over git-over-SSH, as an alternative to the REST transport.
 *
 * What it buys is authentication with a key rather than a token: the private
 * half is generated on the device and never moves, and the public half goes to
 * GitHub as a read-only deploy key scoped to one repository. No expiry to
 * chase.
 *
 * What it costs is size. Git has no way to fetch a subset of paths -- there is
 * no sparse-checkout or partial clone in JGit -- so this is the full history
 * and every attachment, where the REST transport takes the markdown alone. The
 * clone is shallow to take the edge off, but it is still several times the
 * footprint.
 *
 * The working tree *is* the vault directory, so a checkout leaves the files
 * exactly where the reader already looks for them and nothing has to be copied.
 */
class GitSshVaultSync(
    private val workTree: File,
    private val remoteUrl: String,
    private val branch: String,
    private val keys: SshKeyStore,
    configDir: File,
    private val filter: VaultFilter = VaultFilter(),
    /** Names this repository's own key, since a deploy key serves only one. */
    private val keyMount: String = "",
    private val shallowDepth: Int = DEFAULT_DEPTH,
    /** Narrates each stage, so a long clone is visibly working. */
    private val log: suspend (String) -> Unit = {},
    val progress: GitProgress = GitProgress(),
) : VaultSync,
    VaultWriter {
    init {
        // Must happen before any other JGit call touches configuration.
        AndroidGitEnvironment.install(configDir)
        SshSessionFactory.setInstance(sessionFactory())
    }

    override suspend fun plan(base: SyncBase): SyncPlan =
        withContext(Dispatchers.IO) {
            checkReachable()
            checkAuthentication()

            val fresh = !File(workTree, Constants.DOT_GIT).isDirectory
            if (fresh) {
                log(
                    "cloning $remoteUrl (depth $shallowDepth) - git cannot fetch a subset, so this is every attachment too",
                )
                try {
                    cloneRepository()
                } catch (failure: Exception) {
                    log("clone threw: " + failure.describeChain())
                    // Name the stage it died at. "Remote hung up" says nothing
                    // about whether it failed immediately or three quarters of
                    // the way through a transfer, and those mean very different
                    // things.
                    log("clone failed during: " + progress.stage.value.ifEmpty { "connection setup" })
                    throw enrich(failure)
                }
                log("clone finished")
            }

            openGit().use { git ->
                if (!fresh) {
                    log("fetching $branch")
                    fetch(git)
                }

                val head =
                    git.repository.resolve("$REMOTE_PREFIX$branch") ?: git.repository.resolve(Constants.HEAD)
                        ?: return@withContext SyncPlan(base.commit, base.commit.orEmpty())
                val headSha = head.name

                if (base.commit == headSha && !fresh) {
                    return@withContext SyncPlan(base.commit, headSha)
                }

                val baseCommit = base.commit
                val entries =
                    if (baseCommit == null) {
                        filesAt(git.repository, head).map { it to ChangeKind.ADDED }
                    } else {
                        diff(git, baseCommit, headSha)
                    }

                buildPlan(base, headSha, entries)
            }
        }

    override suspend fun apply(
        plan: SyncPlan,
        sink: VaultSink,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        openGit().use { git ->
            // Git writes the working tree itself, so "applying" is a checkout
            // plus recording what is now on disk. Nothing is downloaded twice.
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("$REMOTE_PREFIX$branch")
                .call()
        }

        val total = plan.adds.size + plan.modifies.size + plan.deletes.size + plan.renames.size
        var done = 0
        (plan.adds + plan.modifies).forEach { entry ->
            // A checkout writes every path, images included -- there is no
            // way to ask git for a subset -- so nothing is ever ABSENT here.
            sink.record(entry, LocalState.DOWNLOADED)
            onProgress(++done, total)
        }
        plan.renames.forEach { rename ->
            sink.move(rename.from, rename.to)
            onProgress(++done, total)
        }
        plan.deletes.forEach { path ->
            sink.delete(path)
            onProgress(++done, total)
        }
    }

    /**
     * Turns a transport failure into something worth reading.
     *
     * A dropped connection part way through is the expected failure on a phone:
     * the transfer is large, git has no way to fetch less, and a clone cannot
     * resume -- every retry starts from zero. Saying so is more use than
     * repeating the remote's own words.
     */
    private fun enrich(failure: Exception): Exception {
        val message = failure.message.orEmpty()
        val dropped = "hung up" in message || "Connection reset" in message || "closed" in message
        // Only a drop once bytes were moving. Claiming "part way through" for a
        // failure at connection setup, as this did, actively misleads.
        if (!dropped || progress.stage.value.isEmpty()) return failure
        return IOException(
            "the connection dropped part way through the clone. This transfer is well over a " +
                "hundred megabytes because git cannot fetch a subset, and a clone cannot resume, " +
                "so each retry starts again. The REST transport pulls the markdown alone and " +
                "resumes where it left off.",
            failure,
        )
    }

    /** The public line to register with the host as a deploy key. */
    suspend fun publicKey(): String = keys.publicKeyLine(keyMount) ?: keys.generate(keyMount)

    /**
     * Whether this key may push, asked by starting a push and stopping at the
     * advertisement.
     *
     * A deploy key is read-only unless it was registered with write access
     * ticked, and nothing about the key itself says which it is -- the fetch
     * side behaves identically either way. GitHub refuses to run `receive-pack`
     * for a read-only key, so opening a push and going no further is the one
     * cheap question that gets a straight answer.
     */
    override suspend fun canPush(): Boolean =
        withContext(Dispatchers.IO) {
            if (!File(workTree, Constants.DOT_GIT).isDirectory) return@withContext false
            runCatching {
                openGit().use { git ->
                    Transport.open(git.repository, URIish(remoteUrl)).use { transport ->
                        if (transport is SshTransport) {
                            transport.sshSessionFactory = SshSessionFactory.getInstance()
                        }
                        transport.timeout = AUTH_TIMEOUT_MS / MILLIS_PER_SECOND
                        transport.openPush().close()
                    }
                }
                true
            }.getOrElse { failure ->
                log("this key cannot push: " + failure.describeChain())
                false
            }
        }

    /**
     * Applies [edit] to [path] and pushes the commit.
     *
     * Fetch and hard reset first, every time: the edit has to be computed
     * against what the branch actually says now, not against whatever this
     * working tree was left holding. That also means a rejected push is
     * answered by going round again -- the reset discards the commit that lost,
     * the edit is re-applied to the new content, and the result is a change
     * that reads as though it were made after the other one rather than instead
     * of it.
     */
    override suspend fun write(
        path: String,
        message: String,
        author: Author,
        edit: TextEdit,
    ): WriteOutcome =
        withContext(Dispatchers.IO) {
            openGit().use { git ->
                repeat(PUSH_ATTEMPTS) {
                    fetch(git)
                    git
                        .reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("$REMOTE_PREFIX$branch")
                        .call()

                    val file = File(workTree, path)
                    val current = if (file.isFile) file.readText() else null
                    val updated = edit.applyTo(current) ?: return@withContext WriteOutcome.NotApplicable
                    if (updated == current) return@withContext WriteOutcome.NotApplicable

                    file.parentFile?.mkdirs()
                    file.writeText(updated)
                    git.add().addFilepattern(path).call()
                    val identity = PersonIdent(author.name, author.email)
                    val commit =
                        git
                            .commit()
                            .setMessage(message)
                            .setAuthor(identity)
                            .setCommitter(identity)
                            .call()

                    val results =
                        git
                            .push()
                            .setRemote(Constants.DEFAULT_REMOTE_NAME)
                            .setRefSpecs(RefSpec("HEAD:$REFS_HEADS$branch"))
                            .setTransportConfigCallback(sshConfig)
                            .setTimeout(TIMEOUT_SECONDS)
                            .call()
                    val rejection = results.firstNotNullOfOrNull { it.rejection() }
                    if (rejection == null) return@withContext WriteOutcome.Written(commit.name, updated)
                    log("push rejected ($rejection) - re-reading and applying the edit again")
                    if (SHALLOW_REFUSED in rejection) {
                        throw IOException(
                            "the repository refused a push from a shallow clone. This vault was " +
                                "cloned one commit deep, which is what keeps it to a size a phone " +
                                "can hold; the REST transport writes without that limit.",
                        )
                    }
                }
            }
            throw IOException("could not push $path after $PUSH_ATTEMPTS attempts")
        }

    /** The first remote ref this push failed on, or null if it all landed. */
    private fun PushResult.rejection(): String? =
        remoteUpdates
            .firstOrNull { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
            ?.let { "${it.status}${it.message?.let { why -> ": $why" }.orEmpty()}" }

    private enum class ChangeKind { ADDED, MODIFIED, DELETED }

    private fun buildPlan(
        base: SyncBase,
        headSha: String,
        changes: List<Pair<VaultEntry, ChangeKind>>,
    ): SyncPlan {
        val adds = mutableListOf<VaultEntry>()
        val modifies = mutableListOf<VaultEntry>()
        val deletes = mutableListOf<String>()

        changes.forEach { (entry, kind) ->
            when (kind) {
                ChangeKind.ADDED -> if (entry.path in base.manifest) modifies += entry else adds += entry
                ChangeKind.MODIFIED -> modifies += entry
                ChangeKind.DELETED -> if (entry.path in base.manifest) deletes += entry.path
            }
        }

        // Same blob at a new path is a move on disk, not a re-read.
        val removedBySha =
            deletes
                .mapNotNull { path -> base.manifest[path]?.let { it to path } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toMutableList() }
        val renames = mutableListOf<Rename>()
        val remainingAdds = mutableListOf<VaultEntry>()
        adds.forEach { entry ->
            val from = removedBySha[entry.sha]?.removeFirstOrNull()
            if (from != null) renames += Rename(from, entry.path, entry.sha) else remainingAdds += entry
        }

        val renamedFrom = renames.mapTo(mutableSetOf()) { it.from }
        return SyncPlan(
            baseCommit = base.commit,
            headCommit = headSha,
            adds = remainingAdds,
            modifies = modifies,
            renames = renames,
            deletes = deletes - renamedFrom,
            unchanged = base.manifest.size - remainingAdds.size - modifies.size - deletes.size,
        )
    }

    private fun cloneRepository() {
        prepareCloneTarget(workTree)
        Git
            .cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(workTree)
            .setBranch(branch)
            .setBranchesToClone(listOf("$REFS_HEADS$branch"))
            // No sparse-checkout in JGit, so depth is the only lever there is.
            .setDepth(shallowDepth)
            .setTransportConfigCallback(sshConfig)
            .setProgressMonitor(progress)
            // Without a timeout a blocked port never fails, it just hangs --
            // and port 22 is blocked on plenty of mobile networks.
            .setTimeout(TIMEOUT_SECONDS)
            .call()
            .close()
    }

    private fun fetch(git: Git) {
        git
            .fetch()
            .setRemote(Constants.DEFAULT_REMOTE_NAME)
            .setDepth(shallowDepth)
            .setTransportConfigCallback(sshConfig)
            .setProgressMonitor(progress)
            .setTimeout(TIMEOUT_SECONDS)
            .call()
    }

    /**
     * Confirms the SSH endpoint answers before handing over to JGit.
     *
     * Without this, a blocked port 22 -- which plenty of mobile networks do --
     * looks exactly like a slow clone: no output, no error, nothing to act on.
     * A short connect attempt turns that into a sentence naming the port and
     * the way round it.
     */
    private suspend fun checkReachable() {
        val (host, port) = endpoint()
        log("checking $host:$port")
        val reachable =
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), REACH_TIMEOUT_MS)
                        true
                    }
                }.getOrDefault(false)
            }
        if (reachable) {
            log("$host:$port reachable")
            return
        }
        val hint =
            if (port == DEFAULT_SSH_PORT) {
                " - many mobile networks block port 22; turn on \"Connect over port 443\" in settings"
            } else {
                ""
            }
        throw IOException("cannot reach $host:$port$hint")
    }

    /**
     * Reaches the host and authenticates, and does nothing else.
     *
     * Exposed because "is this key registered?" is the question that is
     * otherwise only answered by starting a sync and reading the failure --
     * which is how a perfectly good deploy key came to be replaced twice.
     */
    suspend fun authenticate() {
        checkReachable()
        checkAuthentication()
    }

    /**
     * Opens an SSH session and authenticates, without transferring anything.
     *
     * GitHub answers a rejected key by closing the connection, which JGit
     * reports as the remote hanging up unexpectedly -- a message that is
     * indistinguishable from a network failure mid-transfer and sent me looking
     * at timeouts and keepalives for hours. Doing the handshake on its own
     * separates the two: if this step fails, the key is the problem, and no
     * amount of retrying will help.
     */
    private suspend fun checkAuthentication() {
        log("authenticating with the SSH key")
        val failure =
            withContext(Dispatchers.IO) {
                runCatching {
                    SshSessionFactory
                        .getInstance()
                        .getSession(URIish(remoteUrl), null, FS.DETECTED, AUTH_TIMEOUT_MS)
                        .disconnect()
                }.exceptionOrNull()
            }
        if (failure == null) {
            log("authenticated")
            return
        }
        // Say exactly what the handshake did before interpreting it.
        log("authentication failed: " + failure.describeChain())

        // An environment failure is not a rejected key, and saying so sent this
        // hunt in the wrong direction for hours. Only claim rejection when the
        // server actually rejected something.
        val chain = generateSequence(failure) { it.cause }.take(CHAIN_DEPTH).toList()
        if (chain.any { it is NoClassDefFoundError || it is ExceptionInInitializerError }) {
            throw IOException(
                "the SSH stack could not start on this device: " + failure.describeChain(),
                failure,
            )
        }

        // Nothing was offered, so nothing can have been refused. This is the
        // third distinct cause this one message has been blamed on, and the
        // expensive one: it reads as a key the repository does not know, so
        // the natural response is to go and replace a deploy key that was
        // never the problem. A key the device cannot read is a local fault and
        // has to say so.
        val fingerprint = keys.fingerprint(keyMount)
        if (NO_KEY_OFFERED in failure.describeChain() || fingerprint in UNUSABLE_KEY) {
            throw IOException(
                "this device could not use its own SSH key, so nothing was sent to the server " +
                    "and nothing was refused. The key is at ${keys.identity(keyMount).name} and reads as " +
                    "\"$fingerprint\". Generating a new one in Settings will not help if the old " +
                    "one was readable before: " + failure.describeChain(),
                failure,
            )
        }

        throw IOException(
            "the repository rejected this SSH key. Its fingerprint is " + fingerprint +
                " - check that exact key is listed as a deploy key on the repository. " +
                "Reinstalling the app or clearing its data generates a new one.",
            failure,
        )
    }

    /** Host and port from either the scp-style or the ssh:// form of the URL. */
    private fun endpoint(): Pair<String, Int> {
        if (remoteUrl.startsWith("ssh://")) {
            val authority = remoteUrl.removePrefix("ssh://").substringBefore('/')
            val hostPart = authority.substringAfter('@', authority)
            val host = hostPart.substringBefore(':')
            val port = hostPart.substringAfter(':', "").toIntOrNull() ?: DEFAULT_SSH_PORT
            return host to port
        }
        val host = remoteUrl.substringAfter('@').substringBefore(':')
        return host to DEFAULT_SSH_PORT
    }

    private fun openGit(): Git = Git.open(workTree)

    private fun filesAt(
        repository: Repository,
        commit: ObjectId,
    ): List<VaultEntry> {
        // One reader for the whole walk. Opening one per file -- as this did
        // -- means thousands of readers over a real vault, which crawls badly
        // enough to look like a hang.
        repository.newObjectReader().use { reader ->
            RevWalk(repository).use { walk ->
                val tree = walk.parseCommit(commit).tree
                TreeWalk(repository).use { treeWalk ->
                    treeWalk.addTree(tree)
                    treeWalk.isRecursive = true
                    val out = mutableListOf<VaultEntry>()
                    while (treeWalk.next()) {
                        val path = treeWalk.pathString
                        val kind = filter.kindOf(path) ?: continue
                        val id = treeWalk.getObjectId(0)
                        out +=
                            VaultEntry(
                                path = path,
                                sha = id.name,
                                size = runCatching { reader.getObjectSize(id, -1) }.getOrDefault(0L),
                                kind = kind,
                            )
                    }
                    return out
                }
            }
        }
    }

    private fun diff(
        git: Git,
        fromSha: String,
        toSha: String,
    ): List<Pair<VaultEntry, ChangeKind>> {
        val repository = git.repository
        val from =
            repository.resolve(fromSha) ?: return filesAt(repository, repository.resolve(toSha)!!)
                .map { it to ChangeKind.ADDED }
        val to = repository.resolve(toSha) ?: return emptyList()

        repository.newObjectReader().use { reader ->
            RevWalk(repository).use { walk ->
                val oldTree = CanonicalTreeParser().apply { reset(reader, walk.parseCommit(from).tree) }
                val newTree = CanonicalTreeParser().apply { reset(reader, walk.parseCommit(to).tree) }
                return git
                    .diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call()
                    .mapNotNull { entry -> entry.toChange(reader) }
            }
        }
    }

    private fun DiffEntry.toChange(reader: org.eclipse.jgit.lib.ObjectReader): Pair<VaultEntry, ChangeKind>? {
        val deleted = changeType == DiffEntry.ChangeType.DELETE
        val path = if (deleted) oldPath else newPath
        val kind = filter.kindOf(path) ?: return null
        val id = if (deleted) oldId.toObjectId() else newId.toObjectId()
        val size = runCatching { reader.getObjectSize(id, -1) }.getOrDefault(0L)
        val entry = VaultEntry(path, id.name, size, kind)
        return entry to
            when (changeType) {
                DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> ChangeKind.ADDED
                DiffEntry.ChangeType.DELETE -> ChangeKind.DELETED
                else -> ChangeKind.MODIFIED
            }
    }

    private val sshConfig =
        TransportConfigCallback { transport ->
            if (transport is SshTransport) transport.sshSessionFactory = SshSessionFactory.getInstance()
        }

    private fun sessionFactory() =
        SshdSessionFactoryBuilder()
            .setHomeDirectory(keys.directory.parentFile)
            .setSshDirectory(keys.directory)
            .setPreferredAuthentications("publickey")
            .setDefaultIdentities { listOf(keys.identity(keyMount).toPath()) }
            .setConfigFile { keys.configFile() }
            // There is no interactive prompt on a phone and no known_hosts to
            // seed, so the host key is accepted on first use and pinned by
            // sshd's own store from then on.
            .setServerKeyDatabase { _, _ -> AcceptFirstConnection() }
            .build(null)

    private class AcceptFirstConnection : ServerKeyDatabase {
        override fun lookup(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            config: ServerKeyDatabase.Configuration?,
        ): List<PublicKey> = emptyList()

        override fun accept(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            serverKey: PublicKey?,
            config: ServerKeyDatabase.Configuration?,
            provider: org.eclipse.jgit.transport.CredentialsProvider?,
        ): Boolean = true
    }

    private companion object {
        const val DEFAULT_DEPTH = 1

        /** sshd's wording when the client had no identity to present at all. */
        const val NO_KEY_OFFERED = "no keys to try"

        /** What [SshKeyStore.fingerprint] returns when it cannot read the key. */
        val UNUSABLE_KEY = setOf("unreadable", "no key")

        // Generous on purpose: this clone moves well over a hundred
        // megabytes, and the client sits idle while the server compresses
        // objects. At sixty seconds that silence alone aborted the transfer.
        const val TIMEOUT_SECONDS = 600
        const val REACH_TIMEOUT_MS = 10_000
        const val AUTH_TIMEOUT_MS = 20_000
        const val CHAIN_DEPTH = 8
        const val DEFAULT_SSH_PORT = 22
        const val REMOTE_PREFIX = "refs/remotes/origin/"
        const val REFS_HEADS = "refs/heads/"
        const val MILLIS_PER_SECOND = 1000
        const val PUSH_ATTEMPTS = 3

        /** What a server says when it will not take a push from a shallow clone. */
        const val SHALLOW_REFUSED = "shallow update not allowed"
    }
}

/**
 * Makes [target] safe to clone into.
 *
 * JGit refuses a destination that exists and is not empty, and the vault
 * directory is full of markdown the moment a REST sync has run -- so switching
 * transports would otherwise fail on the first attempt with
 * "already exists and is not an empty directory".
 *
 * Clearing it is safe because the directory is purely a cache of the remote:
 * the clone rebuilds every file, and the manifest is keyed by git blob sha, so
 * it survives the switch unchanged.
 */
internal fun prepareCloneTarget(target: File) {
    if (File(target, org.eclipse.jgit.lib.Constants.DOT_GIT).isDirectory) return
    target.listFiles()?.forEach { it.deleteRecursively() }
    target.mkdirs()
}
