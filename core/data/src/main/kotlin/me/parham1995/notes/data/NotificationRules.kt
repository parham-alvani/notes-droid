package me.parham1995.notes.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.obsidian.NotificationRule
import me.parham1995.notes.obsidian.SettingsFromVault
import me.parham1995.notes.obsidian.VaultSettingsDecline
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** A notification rule and the vault whose `.daftar.toml` says it. */
data class VaultRule(
    val vaultId: Long,
    val rule: NotificationRule,
) {
    /** The rule's job, by name: two vaults may each have a rule called `diary`. */
    val workName: String get() = "notification-rule.$vaultId.${rule.id}"
}

/**
 * The notification rules of every vault (`notification-rules.md` in
 * notes-linux's `docs/specs/`), and the day each was last checked.
 *
 * Every vault, not only the one being read: a rule is about a note of its own
 * vault, and a diary in the second repository is as easy to forget as one in
 * the first. The rules are read from each vault's file as the settings are,
 * so a device that does not follow its vaults' settings has none.
 */
@Singleton
class NotificationRules
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val vaults: VaultDao,
        private val config: VaultConfigStore,
    ) {
        private val preferences = context.getSharedPreferences("notification_rules", Context.MODE_PRIVATE)

        /** Every rule of every vault that syncs, vault by vault, each in the order its file writes them. */
        suspend fun all(decline: VaultSettingsDecline): List<VaultRule> =
            vaults.all().filter { it.enabled }.flatMap { vault -> of(vault.id, decline) }

        /** The rule [id] of [vaultId] as its file says it now, or null when it says none. */
        suspend fun one(
            vaultId: Long,
            id: String,
            decline: VaultSettingsDecline,
        ): NotificationRule? = of(vaultId, decline).firstOrNull { it.rule.id == id }?.rule

        private suspend fun of(
            vaultId: Long,
            decline: VaultSettingsDecline,
        ): List<VaultRule> {
            val file = config.of(vaultId)
            val said = SettingsFromVault.resolved(file.text, decline = decline, root = file.root)
            return NotificationRule.all(said, file.root).map { VaultRule(vaultId, it) }
        }

        /** The day [rule] was last checked, or null when it never was. */
        fun checkedOn(rule: VaultRule): LocalDate? =
            preferences.getString(rule.workName, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        fun markChecked(
            rule: VaultRule,
            day: LocalDate,
        ) = preferences.edit { putString(rule.workName, day.toString()) }
    }
