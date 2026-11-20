package me.parham1995.notes.data

import kotlinx.coroutines.flow.first
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.obsidian.DaftarToml
import me.parham1995.notes.obsidian.SettingsFromVault
import javax.inject.Inject
import javax.inject.Singleton

/** Where a change of a setting went. */
enum class SettingHome {
    /** The vault's `.daftar.toml`: the phone's sync commits and pushes it, and the other devices get it. */
    VAULT,

    /** The phone's own settings. */
    DEVICE,
}

/**
 * Changes one of the app's settings, in the vault where the vault may hold
 * it: `vault-config.md` in notes-linux's `docs/specs/`, section 5, which
 * Daftar for Linux and the Mac follow too.
 *
 * The person keeps the settings in the repository, so a change made on the
 * phone is written to the vault's `.daftar.toml` through the app's ordinary
 * vault write ([VaultWriteRepository.setSetting]), one key's line and no
 * other, and reaches the other devices with the next sync. The phone's own
 * settings keep only what a repository may never hold and what the person
 * chose to keep here: a change goes to the device when the key is declined,
 * when the vault is read-only or cannot be written, and when the value is
 * one a vault may not hold.
 *
 * **Which vault.** The one the person is in: the vault being read
 * (`activeVaultId`), which the browser, search and the tasks all show, and
 * the first vault when none has been chosen yet. The same vault whose
 * `.daftar.toml` the settings are read from, so a change is seen where it
 * was written. With no vault at all, every change is the device's.
 *
 * Everything that changes a setting a vault may hold goes through here: the
 * Settings screens, and what changes one from elsewhere (a pinch in a note,
 * the SSH screen's port). A setter of [SettingsStore] called directly writes
 * the device and nothing else.
 */
@Singleton
class SettingsWriter
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val writes: VaultWriteRepository,
        private val vaultConfig: VaultConfigSource,
        private val vaults: VaultDao,
    ) {
        /** The vault a change is written to: the one being read, else the first, else none. */
        suspend fun vaultId(): Long? {
            val active = settings.own.first().activeVaultId
            val all = vaults.all()
            return (all.firstOrNull { it.id == active } ?: all.firstOrNull())?.id
        }

        /** Whether a change can go to that vault now: it can be written, and is not read-only. */
        suspend fun writable(): Boolean = vaultId()?.let { it in writes.writable.first() } == true

        /** Changes [binding] to [value], and says where the change went. */
        suspend fun <T : Any> set(
            binding: SettingBinding<T>,
            value: T,
        ): SettingHome {
            val own = settings.own.first()
            val vaultId = vaultId()
            val declined = !own.fromVault.follow || binding.id in own.fromVault.declined
            if (vaultId != null && !declined) {
                val file = vaultConfig.of(vaultId)
                val keys = DaftarToml.parse(file.text.orEmpty())
                val said = binding.said(value, keys)
                val writable = vaultId in writes.writable.first()
                val home =
                    said?.let { (key, toml) ->
                        SettingsFromVault.home(file.text, key, toml, writable, root = file.root)
                    }
                if (said != null && home is SettingsFromVault.Home.Vault) {
                    val result = writes.setSetting(vaultId, home.key, said.second)
                    if (result !is WriteResult.Refused) {
                        settings.vaultConfigChanged()
                        return SettingHome.VAULT
                    }
                }
                // A value a vault may not hold (pictures from the web turned
                // on) is the device's. Where the vault says otherwise the
                // person's choice would change nothing, so the key is kept on
                // this device from here on, as Keep on This Device does.
                val vaultSays = binding.read(SettingsFromVault.resolved(file.text, root = file.root), file.root) != null
                if (writable && vaultSays) settings.setVaultKeyDeclined(binding.id, declined = true)
            }
            binding.keep(settings, value)
            return SettingHome.DEVICE
        }

        /**
         * Keep on This Device: the key joins the declined list, and the
         * value in use now is written to the device, so nothing changes on
         * screen. The vault's line stays, for the other devices.
         */
        suspend fun <T : Any> keepOnDevice(binding: SettingBinding<T>) {
            val value = binding.get(settings.current())
            binding.keep(settings, value)
            settings.setVaultKeyDeclined(binding.id, declined = true)
        }

        /** Follow the Vault: the key leaves the declined list, and the vault's value is used again. */
        suspend fun followVault(binding: SettingBinding<*>) = settings.setVaultKeyDeclined(binding.id, declined = false)
    }
