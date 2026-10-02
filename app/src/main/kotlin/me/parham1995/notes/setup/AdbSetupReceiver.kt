package me.parham1995.notes.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncRepository
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.TokenStore
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import javax.inject.Inject

/**
 * The app driven from a terminal: `adb shell am broadcast` with a command
 * in the extras, the answer in the broadcast's result data. `tools/daftar`
 * wraps the invocation.
 *
 * It exists because setting a vault up through the screens over adb means
 * typing into text fields under a predictive keyboard, which rewrote a
 * repository name three ways in one afternoon, and because the one phone
 * this app lives on has to be set up again whenever its data is lost.
 *
 * Only the shell may call it. The receiver is exported, as a broadcast from
 * `am` requires, but guarded by `android.permission.DUMP` in the manifest,
 * which the shell and the system hold and no installed app can be granted.
 * Anything that reaches here already had adb, and adb can do far worse than
 * add a vault -- `pm clear`, for one -- so the commands are not a new door.
 */
@AndroidEntryPoint
class AdbSetupReceiver : BroadcastReceiver() {
    @Inject lateinit var sync: SyncRepository

    @Inject lateinit var vaults: VaultRepository

    @Inject lateinit var keys: SshKeyStore

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var tokens: TokenStore

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val extras = intent.extras?.let { bundle -> bundle.keySet().associateWith { bundle.getString(it) } }.orEmpty()
        // The work suspends -- the database, the key generator, a handshake
        // with GitHub -- and a receiver's thread may not. goAsync holds the
        // broadcast open until the answer is set, and `am` prints it.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val outcome = SetupCommand.parse(extras).mapCatching { run(it) }
            pending.setResultCode(if (outcome.isSuccess) 0 else 1)
            pending.setResultData(outcome.getOrElse { "error: ${it.message ?: it::class.simpleName}" })
            pending.finish()
        }
    }

    private suspend fun run(command: SetupCommand): String =
        when (command) {
            SetupCommand.Status -> status()
            is SetupCommand.AddVault -> {
                val first = vaults.vaults().first().isEmpty()
                val id =
                    sync.addVault(
                        owner = command.owner,
                        repo = command.repo,
                        branch = command.branch,
                        name = command.name,
                        transport = command.transport ?: settings.current().transport,
                    )
                // As the settings screen does: the first vault also fills the
                // older single-repository settings the token screen reads.
                if (first) settings.setRepository(command.owner, command.repo, command.branch)
                "added vault $id ${command.owner}/${command.repo}"
            }
            is SetupCommand.RemoveVault -> {
                val vault = find(command.vault)
                sync.removeVault(vault.id)
                keys.delete(vault.id)
                "removed ${vault.label}"
            }
            is SetupCommand.SetTransport -> {
                val vault = find(command.vault)
                sync.setTransport(vault.id, command.transport)
                "${vault.label} now syncs over ${command.transport.name}"
            }
            is SetupCommand.Key -> {
                val vault = find(command.vault)
                if (command.replace || !keys.exists(vault.id)) keys.generate(vault.id)
                keys.publicKeyLine(vault.id) ?: error("no key for ${vault.label}")
            }
            is SetupCommand.TestKey -> {
                val vault = find(command.vault)
                sync.testSshKey(vault).fold(onSuccess = { "ok: $it" }, onFailure = { error("failed: ${it.message}") })
            }
            is SetupCommand.SetToken -> {
                tokens.setToken(command.token)
                "token stored"
            }
            is SetupCommand.SetAuthor -> {
                settings.setAuthor(command.name, command.email)
                "author set to ${command.name} <${command.email}>"
            }
            SetupCommand.Sync -> {
                scheduler.syncNow(settings.current().syncOnWifiOnly)
                "sync requested"
            }
        }

    private suspend fun status(): String {
        val all = vaults.vaults().first()
        if (all.isEmpty()) return "no vaults"
        return all.joinToString("\n") { vault ->
            val key = if (keys.exists(vault.id)) keys.fingerprint(vault.id) else "no key"
            listOfNotNull(
                "#${vault.id}",
                vault.label,
                "${vault.owner}/${vault.repo}",
                vault.branch,
                SyncTransport.parse(vault.transport).name.lowercase(),
                if (vault.canWrite) "writable" else "read-only",
                key,
                vault.lastError?.let { "error: $it" },
            ).joinToString("  ")
        }
    }

    /** By label, by repository name, or by id -- whichever was typed. */
    private suspend fun find(ref: String): VaultEntity {
        val all = vaults.vaults().first()
        return all.firstOrNull { it.id.toString() == ref }
            ?: all.firstOrNull { it.label.equals(ref, ignoreCase = true) }
            ?: all.firstOrNull { it.repo.equals(ref, ignoreCase = true) }
            ?: error("no vault '$ref'; have ${all.joinToString { it.label }}")
    }
}
