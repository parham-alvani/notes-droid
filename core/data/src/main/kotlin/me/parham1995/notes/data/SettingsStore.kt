package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** When images are fetched. Markdown is always synced. */
enum class ImagePolicy {
    /** Fetch an image the first time a note embeds it. The default. */
    ON_DEMAND,

    /** Pull every image during sync, but only on an unmetered network. */
    PREFETCH_ON_WIFI,

    /** Never fetch images; show a placeholder instead. */
    NEVER,
    ;

    companion object {
        fun parse(raw: String?): ImagePolicy = entries.firstOrNull { it.name == raw } ?: ON_DEMAND
    }
}

/** Which repository to read and how. Nothing about the vault is compiled in. */
data class VaultSettings(
    val owner: String = "",
    val repo: String = "",
    /** Null means "whatever the repository's default branch is". */
    val branch: String? = null,
    val imagePolicy: ImagePolicy = ImagePolicy.ON_DEMAND,
    val syncOnWifiOnly: Boolean = false,
) {
    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank()
}

@Singleton
class SettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val settings: Flow<VaultSettings> =
            context.settingsDataStore.data.map { preferences ->
                VaultSettings(
                    owner = preferences[OWNER].orEmpty(),
                    repo = preferences[REPO].orEmpty(),
                    branch = preferences[BRANCH]?.takeIf { it.isNotBlank() },
                    imagePolicy = ImagePolicy.parse(preferences[IMAGE_POLICY]),
                    syncOnWifiOnly = preferences[WIFI_ONLY] ?: false,
                )
            }

        suspend fun current(): VaultSettings = settings.first()

        suspend fun setRepository(
            owner: String,
            repo: String,
            branch: String?,
        ) {
            context.settingsDataStore.edit {
                it[OWNER] = owner.trim()
                it[REPO] = repo.trim()
                if (branch.isNullOrBlank()) it.remove(BRANCH) else it[BRANCH] = branch.trim()
            }
        }

        suspend fun setImagePolicy(policy: ImagePolicy) {
            context.settingsDataStore.edit { it[IMAGE_POLICY] = policy.name }
        }

        suspend fun setSyncOnWifiOnly(enabled: Boolean) {
            context.settingsDataStore.edit { it[WIFI_ONLY] = enabled }
        }

        private companion object {
            val OWNER = stringPreferencesKey("repo_owner")
            val REPO = stringPreferencesKey("repo_name")
            val BRANCH = stringPreferencesKey("repo_branch")
            val IMAGE_POLICY = stringPreferencesKey("image_policy")
            val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        }
    }
