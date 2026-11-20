package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.AndroidGitEnvironment
import me.parham1995.notes.data.git.GitSshVaultSync
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.markdown.PropertyEdit
import me.parham1995.notes.markdown.PropertyValue
import me.parham1995.notes.sync.ReadOnlyVault
import me.parham1995.notes.sync.VaultWriter
import okhttp3.OkHttpClient
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * A read-only vault and the vault's own settings, through the app's one
 * write path and its settings store, on a real clone of a repository on this
 * disk: the tests `read-only-vault.md` and `vault-config.md` in notes-linux's
 * `docs/specs/` ask of every app that need more than a pure function, by the
 * names they give them.
 */
@RunWith(RobolectricTestRunner::class)
class ReadOnlyVaultTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var settings: SettingsStore
    private lateinit var clones: VaultClones
    private lateinit var writes: VaultWriteRepository
    private lateinit var writer: SettingsWriter
    private lateinit var root: File
    private lateinit var remote: File
    private lateinit var tree: File

    private val vaultId = 1L

    @Before
    fun setUp() =
        runTest {
            context.settingsDataStore.edit { it.clear() }
            database = testDatabase()
            files = VaultFileStore(context)
            root = Files.createTempDirectory("read-only-vault").toFile()
            AndroidGitEnvironment.install(File(root, "config"))
            remote = File(root, "remote.git")
            tree = files.rootOf(vaultId)

            // A vault on a remote, and the phone's clone of it where the app keeps its files.
            Git
                .init()
                .setBare(true)
                .setDirectory(remote)
                .setInitialBranch(BRANCH)
                .call()
                .close()
            val desk = File(root, "desk")
            Git.init().setDirectory(desk).setInitialBranch(BRANCH).call().use { git ->
                git
                    .remoteAdd()
                    .setName("origin")
                    .setUri(URIish("file://" + remote.absolutePath))
                    .call()
                File(desk, "Tasks").mkdirs()
                File(desk, TASKS).writeText("## Alpha\n\n- [ ] first\n")
                File(desk, NOTE).writeText("---\ntitle: Old\n---\n\nA note.\n")
                File(desk, SCRATCHPAD).writeText("## 2026-01-01\n\n- 09:00 a thought\n")
                File(desk, DAFTAR).writeText(VAULT_SETTINGS)
                git.add().addFilepattern(".").call()
                val author = PersonIdent("A Desk", "desk@example.com")
                git
                    .commit()
                    .setMessage("seed")
                    .setAuthor(author)
                    .setCommitter(author)
                    .call()
                git
                    .push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("$BRANCH:$BRANCH"))
                    .call()
            }
            tree.deleteRecursively()
            Git
                .cloneRepository()
                .setURI("file://" + remote.absolutePath)
                .setDirectory(tree)
                .setBranch(BRANCH)
                .call()
                .close()

            val vaultConfig = VaultConfigStore(files, database.vaultDao())
            settings = SettingsStore(context, vaultConfig)
            val gate = VaultGate()
            clones = VaultClones(context, files, database.vaultDao(), gate)
            val log = SyncLog(database.syncLogDao())
            val transports =
                object : VaultTransports(
                    settings = settings,
                    tokens = TokenStore(context),
                    files = files,
                    sshKeys = SshKeyStore(context),
                    log = log,
                    context = context,
                    http = OkHttpClient(),
                    vaults = database.vaultDao(),
                ) {
                    // The app's own transport on the clone, with no key to ask
                    // for: the remote is a folder on this disk.
                    override suspend fun writer(vault: VaultEntity): VaultWriter =
                        GitSshVaultSync(
                            workTree = tree,
                            cloneUrl = "file://" + remote.absolutePath,
                            branch = BRANCH,
                            keys = SshKeyStore(context),
                            vaultId = vault.id,
                            configDir = File(root, "config"),
                            shallowDepth = 0,
                        )
                }
            val indexer =
                VaultIndexer(
                    files = files,
                    notes = database.noteDao(),
                    links = database.linkDao(),
                    headings = database.headingDao(),
                    tasks = database.taskDao(),
                    index = database.indexDao(),
                    search = SearchIndex(database),
                    aliases = database.aliasDao(),
                )
            database.vaultDao().insert(
                VaultEntity(
                    id = vaultId,
                    owner = "someone",
                    repo = "vault",
                    name = "vault",
                    transport = SyncTransport.SSH.name,
                    canWrite = true,
                ),
            )
            indexer.indexAll(vaultId, listOf(PathAndSha(TASKS, "sha-seed")))
            writes =
                VaultWriteRepository(
                    settings = settings,
                    vaults = database.vaultDao(),
                    pending = database.pendingEditDao(),
                    blobs = database.blobDao(),
                    files = files,
                    indexer = indexer,
                    transports = transports,
                    log = log,
                    gate = gate,
                    readOnly = clones,
                    vaultConfig = vaultConfig,
                )
            writer = SettingsWriter(settings, writes, vaultConfig, database.vaultDao())
        }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
            root.deleteRecursively()
        }

    /** Every file of the vault and its bytes, the clone's own `.git` aside. */
    private fun bytes(): Map<String, List<Byte>> =
        tree
            .walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile }
            .associate { it.relativeTo(tree).path to it.readBytes().toList() }

    /** `git status --porcelain`, as the paths it would list. */
    private fun changed(): Set<String> =
        Git.open(tree).use { git ->
            val status = git.status().call()
            status.modified + status.changed + status.added + status.removed + status.missing + status.untracked
        }

    private fun tip(of: File): String = Git.open(of).use { it.repository.resolve("refs/heads/$BRANCH").name }

    /** Who the remote's newest commit is by. */
    private fun lastAuthor(): PersonIdent =
        Git.open(remote).use { git ->
            git
                .log()
                .add(git.repository.resolve("refs/heads/$BRANCH"))
                .setMaxCount(1)
                .call()
                .first()
                .authorIdent
        }

    @Test
    fun aReadOnlyVaultRefusesEveryWrite() =
        runTest {
            settings.setAuthor("A Person", "person@example.com")
            val task =
                database
                    .taskDao()
                    .open(vaultId)
                    .first()
                    .single()
            // Before it is read-only, the vault can be written: the refusals below are the switch's.
            assertThat(writes.writable.first()).containsExactly(vaultId)

            clones.setReadOnly(vaultId, true)
            val before = bytes()
            val head = tip(tree)

            val refusals =
                mapOf(
                    "a write" to writes.writeGenerated(vaultId, "Calendar/2026-W01.md", "# Week\n", "docs: week"),
                    "a task tick" to writes.completeTask(task),
                    "a status" to writes.setTaskStatus(task, '/'),
                    "a capture" to writes.capture("a thought"),
                    "a new task" to writes.addTask(vaultId, TASKS, "Alpha", "second"),
                    "a new note" to writes.createNote(vaultId, "New.md", "# New\n"),
                    "a move" to writes.rescheduleTask(task, "2026-12-01").result,
                    "a sort" to writes.sortTasks(vaultId, TASKS),
                    "a property" to
                        writes.setProperty(
                            vaultId,
                            NOTE,
                            "title: Old\n",
                            PropertyEdit.Set("title", PropertyValue.Text("New")),
                        ),
                    "a restore" to writes.restore(vaultId, NOTE, head, "restored\n"),
                    "a verification" to writes.markVerified(vaultId, NOTE),
                )

            refusals.forEach { (what, result) ->
                assertThat(result).isEqualTo(WriteResult.Refused(ReadOnlyVault.NOTICE, readOnly = true))
                assertThat((result as WriteResult.Refused).readOnly).isTrue()
                assertThat(what).isNotEmpty()
            }
            // Every file has the bytes it had, and git has nothing to report.
            assertThat(bytes()).isEqualTo(before)
            assertThat(changed()).isEmpty()
            assertThat(tip(tree)).isEqualTo(head)
            assertThat(tip(remote)).isEqualTo(head)
            // Nothing waits to be sent either, and nothing is offered.
            assertThat(database.pendingEditDao().all()).isEmpty()
            assertThat(writes.writable.first()).isEmpty()
            assertThat(writes.flush()).isEqualTo(0)

            // Turning it off changes that one key, and the vault is written again.
            clones.setReadOnly(vaultId, false)
            assertThat(writes.createNote(vaultId, "New.md", "# New\n")).isEqualTo(WriteResult.Pushed)
            assertThat(tip(remote)).isNotEqualTo(head)
        }

    /**
     * On Android the app's own author setting is the first of the three, and
     * there is no global config a commit could fall back to: a commit with no
     * author is not made at all.
     */
    @Test
    fun theAuthorIsTheClonesThenTheVaults() =
        runTest {
            // A work author in the config git would read for every repository: never used here.
            val user = SystemReader.getInstance().userConfig
            user.setString("user", null, "name", "Work")
            user.setString("user", null, "email", "work@example.com")
            user.save()
            try {
                val head = tip(remote)

                // With nothing else: the device declines the vault's settings and has no author of its own.
                settings.setFollowVault(false)
                assertThat(writes.authorFor(vaultId).isUsable).isFalse()
                assertThat(
                    writes.createNote(vaultId, "One.md", "# One\n"),
                ).isInstanceOf(WriteResult.Refused::class.java)
                assertThat(tip(remote)).isEqualTo(head)

                // With the vault's git.name and git.email, the commit is the vault's.
                settings.setFollowVault(true)
                assertThat(writes.createNote(vaultId, "One.md", "# One\n")).isEqualTo(WriteResult.Pushed)
                assertThat(lastAuthor().name).isEqualTo("Vault Author")
                assertThat(lastAuthor().emailAddress).isEqualTo("vault@example.com")
                assertThat(lastAuthor().name).isNotEqualTo("Work")

                // With the app's own email set too, the email is the app's and the name the vault's.
                settings.setAuthor("", "phone@example.com")
                assertThat(writes.createNote(vaultId, "Two.md", "# Two\n")).isEqualTo(WriteResult.Pushed)
                assertThat(lastAuthor().name).isEqualTo("Vault Author")
                assertThat(lastAuthor().emailAddress).isEqualTo("phone@example.com")

                // And with both of the app's own, the vault's are not used.
                settings.setAuthor("A Person", "phone@example.com")
                assertThat(writes.createNote(vaultId, "Three.md", "# Three\n")).isEqualTo(WriteResult.Pushed)
                assertThat(lastAuthor().name).isEqualTo("A Person")
            } finally {
                user.unsetSection("user", null)
                user.save()
            }
        }

    /** The vault's `.daftar.toml` as the remote holds it at its tip. */
    private fun remoteSettings(): String =
        Git.open(remote).use { git ->
            val tip = git.repository.resolve("refs/heads/$BRANCH")
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                val tree = walk.parseCommit(tip).tree
                org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository, DAFTAR, tree).use { found ->
                    String(git.repository.open(found.getObjectId(0)).bytes)
                }
            }
        }

    private fun lastMessage(): String =
        Git.open(remote).use { git ->
            git
                .log()
                .add(git.repository.resolve("refs/heads/$BRANCH"))
                .setMaxCount(1)
                .call()
                .first()
                .fullMessage
        }

    /**
     * `vault-config.md` section 5, through the app's settings and its one
     * write path, on the phone's own clone; the pure rule and the file's
     * bytes are `SettingsInVaultTest` in core/obsidian.
     */
    @Test
    fun aSettingChangedInTheAppGoesToTheVault() =
        runTest {
            settings.setAuthor("A Person", "person@example.com")
            val before = File(tree, DAFTAR).readText()
            val device = settings.own.first()

            // A shared key the vault has: only its line changes, and the change is committed and pushed.
            assertThat(writer.set(VaultOverlay.theme, ThemeChoice.SYSTEM)).isEqualTo(SettingHome.VAULT)
            assertThat(
                File(tree, DAFTAR).readText(),
            ).isEqualTo(before.replace("theme = \"light\"", "theme = \"system\""))
            assertThat(remoteSettings()).isEqualTo(File(tree, DAFTAR).readText())
            assertThat(lastMessage()).isEqualTo("chore(settings): set appearance.theme")
            assertThat(settings.current().reading.theme).isEqualTo(ThemeChoice.SYSTEM)

            // A scale that is no whole size is said in `[android]`, at the end of the file.
            assertThat(writer.set(VaultOverlay.textSize, 1.15f)).isEqualTo(SettingHome.VAULT)
            assertThat(remoteSettings()).endsWith("\n[android.appearance]\ntext_scale = 115\n")
            assertThat(settings.current().reading.textScale).isEqualTo(1.15f)
            // And once it is there, that is the line a later change goes to, the shared size left as it was.
            assertThat(writer.set(VaultOverlay.textSize, 1f)).isEqualTo(SettingHome.VAULT)
            assertThat(remoteSettings()).contains("text_scale = 100\n")
            assertThat(remoteSettings()).contains("\ntext_size = 16\n")
            assertThat(settings.current().reading.textScale).isEqualTo(1f)

            // A setting only this app has, and one in hours said in the spec's minutes.
            assertThat(writer.set(VaultOverlay.persianFont, false)).isEqualTo(SettingHome.VAULT)
            assertThat(writer.set(VaultOverlay.syncInterval, 12)).isEqualTo(SettingHome.VAULT)
            assertThat(remoteSettings()).contains("text_scale = 100\npersian_font = false\n")
            assertThat(remoteSettings()).contains("sync_interval = 720\n")
            assertThat(settings.current().reading.persianFont).isFalse()
            assertThat(settings.current().syncIntervalHours).isEqualTo(12)

            // The phone's own settings are as they were, and the file is no note.
            assertThat(settings.own.first()).isEqualTo(device)
            assertThat(database.noteDao().byPath(vaultId, DAFTAR)).isNull()
            assertThat(changed()).isEmpty()
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun whatAVaultMayNotHoldGoesToTheDevice() =
        runTest {
            settings.setAuthor("A Person", "person@example.com")
            val before = File(tree, DAFTAR).readBytes().toList()
            val head = tip(remote)

            // Pictures from the web turned on: a vault may only turn them off. The
            // vault says off, so the key is kept on this device and the choice holds.
            assertThat(settings.current().reading.remoteImages).isFalse()
            assertThat(writer.set(VaultOverlay.remoteImages, true)).isEqualTo(SettingHome.DEVICE)
            assertThat(
                settings.own
                    .first()
                    .reading.remoteImages,
            ).isTrue()
            assertThat(settings.current().reading.remoteImages).isTrue()
            assertThat(
                settings.own
                    .first()
                    .fromVault.declined,
            ).containsExactly(VaultOverlay.REMOTE_IMAGES)

            // With the vault's settings declined, every change is the device's.
            settings.setFollowVault(false)
            assertThat(writer.set(VaultOverlay.theme, ThemeChoice.SYSTEM)).isEqualTo(SettingHome.DEVICE)
            assertThat(
                settings.own
                    .first()
                    .reading.theme,
            ).isEqualTo(ThemeChoice.SYSTEM)
            settings.setFollowVault(true)

            // So is every change in a read-only clone.
            clones.setReadOnly(vaultId, true)
            assertThat(writer.writable()).isFalse()
            assertThat(writer.set(VaultOverlay.digest, false)).isEqualTo(SettingHome.DEVICE)
            assertThat(writer.set(VaultOverlay.keepScreenOn, true)).isEqualTo(SettingHome.DEVICE)
            assertThat(settings.own.first().taskDigest).isFalse()
            assertThat(
                settings.own
                    .first()
                    .reading.keepScreenOn,
            ).isTrue()

            // `.daftar.toml` is byte for byte as it was, here and on the remote.
            assertThat(File(tree, DAFTAR).readBytes().toList()).isEqualTo(before)
            assertThat(tip(remote)).isEqualTo(head)
            assertThat(changed()).isEmpty()
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun keepOnThisDeviceDeclinesTheKey() =
        runTest {
            settings.setAuthor("A Person", "person@example.com")
            val before = File(tree, DAFTAR).readBytes().toList()
            assertThat(settings.current().reading.theme).isEqualTo(ThemeChoice.LIGHT)

            writer.keepOnDevice(VaultOverlay.theme)

            // The key is declined, the value in use is the device's now, and the vault's line stays.
            assertThat(
                settings.own
                    .first()
                    .fromVault.declined,
            ).containsExactly(VaultOverlay.THEME)
            assertThat(
                settings.own
                    .first()
                    .reading.theme,
            ).isEqualTo(ThemeChoice.LIGHT)
            assertThat(settings.current().reading.theme).isEqualTo(ThemeChoice.LIGHT)
            assertThat(VaultOverlay.THEME in settings.current().fromVault).isFalse()
            assertThat(File(tree, DAFTAR).readBytes().toList()).isEqualTo(before)

            // A later change of it writes the device.
            assertThat(writer.set(VaultOverlay.theme, ThemeChoice.DARK)).isEqualTo(SettingHome.DEVICE)
            assertThat(settings.current().reading.theme).isEqualTo(ThemeChoice.DARK)
            assertThat(File(tree, DAFTAR).readBytes().toList()).isEqualTo(before)

            // Follow the Vault takes the key off the list, and the vault's value is used again.
            writer.followVault(VaultOverlay.theme)
            assertThat(
                settings.own
                    .first()
                    .fromVault.declined,
            ).isEmpty()
            assertThat(settings.current().reading.theme).isEqualTo(ThemeChoice.LIGHT)
            assertThat(VaultOverlay.THEME in settings.current().fromVault).isTrue()
        }

    /** Holds now only for a key the device keeps: here, every key of a read-only clone. */
    @Test
    fun aSettingChangedInTheAppGoesToTheComputer() =
        runTest {
            val daftar = File(tree, DAFTAR)
            val before = daftar.readBytes().toList()
            // The vault's 16 over the app's own, as a scale of the desktop's 14.
            assertThat(settings.current().reading.textScale).isEqualTo(16 / 14f)
            assertThat(VaultOverlay.TEXT_SIZE in settings.current().fromVault).isTrue()

            // A read-only clone is never written: the changes are the device's.
            clones.setReadOnly(vaultId, true)
            assertThat(writer.set(VaultOverlay.textSize, 1.3f)).isEqualTo(SettingHome.DEVICE)
            assertThat(writer.set(VaultOverlay.theme, ThemeChoice.LIGHT)).isEqualTo(SettingHome.DEVICE)

            // The app's own store has the change, and the vault's file is as it was, byte for byte.
            assertThat(
                settings.own
                    .first()
                    .reading.textScale,
            ).isEqualTo(1.3f)
            assertThat(
                settings.own
                    .first()
                    .reading.theme,
            ).isEqualTo(ThemeChoice.LIGHT)
            assertThat(daftar.readBytes().toList()).isEqualTo(before)
            assertThat(changed()).isEmpty()
            // The vault still wins, until this device declines the key.
            assertThat(settings.current().reading.textScale).isEqualTo(16 / 14f)
            settings.setVaultKeyDeclined(VaultOverlay.TEXT_SIZE, declined = true)
            assertThat(settings.current().reading.textScale).isEqualTo(1.3f)
            assertThat(VaultOverlay.TEXT_SIZE in settings.current().fromVault).isFalse()
            assertThat(settings.current().fromVault.offered).contains(VaultOverlay.TEXT_SIZE)
            assertThat(daftar.readBytes().toList()).isEqualTo(before)
        }

    @Test
    fun `the vault's settings come over the app's own, each as this app has it`() =
        runTest {
            settings.setSyncIntervalHours(6)
            settings.setRemoteImages(true)

            val followed = settings.current()

            assertThat(followed.reading.theme).isEqualTo(ThemeChoice.LIGHT)
            assertThat(followed.reading.lineWidth).isEqualTo(LineWidth.FULL)
            assertThat(followed.reading.remoteImages).isFalse()
            assertThat(followed.reading.foldSections).isEqualTo(FoldSections.ALWAYS)
            // Ninety minutes is rounded up to whole hours.
            assertThat(followed.backgroundSync).isTrue()
            assertThat(followed.syncIntervalHours).isEqualTo(2)
            assertThat(followed.taskDigest).isTrue()
            assertThat(followed.taskDigestHour).isEqualTo(7)
            assertThat(followed.write.scratchpadPath).isEqualTo("Inbox.md")
            assertThat(followed.reading.hiddenNames).containsExactly("CLAUDE.md", "NOTES.md")
            assertThat(followed.reading.showHiddenFiles).isFalse()
            // The picture is a file of the vault, on this device.
            assertThat(followed.reading.vaultHomePicture).isEqualTo(File(tree, NOTE).path)
            // A key this app has no setting for, and one a vault may not set, change nothing.
            assertThat(followed.fromVault.applied).containsNoneOf("appearance.font", "terminal.shell")
            assertThat(followed.fromVault.applied).containsAtLeast(VaultOverlay.THEME, VaultOverlay.SYNC_INTERVAL)

            // The switch: no app setting is read from any vault.
            settings.setFollowVault(false)
            val own = settings.current()
            assertThat(own.reading.theme).isEqualTo(ThemeChoice.DARK)
            assertThat(own.syncIntervalHours).isEqualTo(6)
            assertThat(own.reading.remoteImages).isTrue()
            assertThat(own.reading.hiddenNames).isNull()
            assertThat(own.fromVault.applied).isEmpty()
            assertThat(own.fromVault.offered).contains(VaultOverlay.THEME)
            // What the vault says of itself is no app setting, and is read either way.
            assertThat(VaultConfigStore(files, database.vaultDao()).daftar(vaultId).folded).isTrue()
            assertThat(VaultConfigStore(files, database.vaultDao()).daftar(vaultId).agentsInReadOnlyClones).isFalse()
        }

    private companion object {
        const val BRANCH = "main"
        const val TASKS = "Tasks/Work.md"
        const val NOTE = "Note.md"
        const val SCRATCHPAD = "Scratchpad.md"
        const val DAFTAR = ".daftar.toml"

        val VAULT_SETTINGS =
            """
            [reader]
            folded = true

            [agents]
            read_only_clones = false

            [appearance]
            theme = "light"
            text_size = 16
            text_width = 0
            remote_images = false
            fold_sections = "always"
            font = "Inter"

            [git]
            sync_interval = 90
            name = "Vault Author"
            email = "vault@example.com"

            [notifications]
            digest = true
            digest_time = "07:30"

            [capture]
            target = "Inbox.md"

            [files]
            hidden = ["CLAUDE.md", "NOTES.md"]
            show_hidden = false

            [home]
            image = "Note.md"

            [terminal]
            shell = "/tmp/x"
            """.trimIndent() + "\n"
    }
}
