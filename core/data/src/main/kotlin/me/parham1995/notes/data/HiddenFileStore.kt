package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.obsidian.HiddenFiles
import me.parham1995.notes.sync.VaultFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one vault's lists leave out, as of now.
 *
 * An interface for the indexer's sake: it marks each note it writes, and its
 * tests have no settings file or `.obsidian` to read the rules from.
 */
fun interface HiddenRules {
    suspend fun forVault(vaultId: Long): HiddenFiles

    companion object {
        /** The app's defaults, for a vault that says nothing of its own. */
        val DEFAULT = HiddenRules { HiddenFiles(names = HiddenFiles.AGENT_FILES) }
    }
}

/**
 * Decides which files the lists leave out, and keeps [NoteEntity.hidden][me.parham1995.notes.data.database.NoteEntity.hidden]
 * saying so.
 *
 * Three things decide it: the vault's own "Excluded files" in
 * `.obsidian/app.json`, whether the agents' files are hidden, and whether
 * hidden files are shown after all. This is the one place they are put
 * together. The indexer asks it for each note it writes; [apply] goes over a
 * vault's notes again when one of the three changes -- after a sync, which
 * may have brought a new `app.json`, and when a setting moves.
 *
 * Nothing here stops a file syncing or a link resolving. A guide used to be
 * left out of the index altogether, which kept it out of every list at once
 * but made a link to it a broken one, and an `uploads/` of attachments cannot
 * be left out like that at all: the notes embed from it.
 */
@Singleton
class HiddenFileStore
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val files: VaultFileStore,
        private val notes: NoteDao,
        private val vaults: VaultDao,
        private val blobs: BlobDao,
        private val log: SyncLog,
    ) : HiddenRules {
        override suspend fun forVault(vaultId: Long): HiddenFiles = rules(vaultId, settings.current().reading)

        /**
         * [vaultId]'s rules, again whenever a setting or the vault's app
         * settings change -- for what the database does not mark, the
         * attachments the browser lists beside the notes.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun rules(vaultId: Long): Flow<HiddenFiles> =
            combine(
                settings.settings.map { it.reading }.distinctUntilChanged { a, b -> a.sameHiding(b) },
                // The manifest's sha for `app.json` moves when the file does,
                // which is the one moment its filters can have changed.
                blobs.shaOf(vaultId, VaultFilter.APP).distinctUntilChanged(),
            ) { reading, _ -> reading }
                .mapLatest { rules(vaultId, it) }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)

        /**
         * Brings every note of [vaultId] in line with the rules, writing only
         * the ones that are wrong -- which after a sync that did not touch
         * `app.json` is none. Answers how many notes it changed.
         */
        suspend fun apply(vaultId: Long): Int {
            val rules = forVault(vaultId)
            val wrong = notes.visibility(vaultId).filter { rules.hides(it.path) != it.hidden }
            if (wrong.isEmpty()) return 0
            val (toHide, toShow) = wrong.partition { !it.hidden }
            toHide.map { it.id }.chunked(BATCH).forEach { notes.setHidden(it, hidden = true) }
            toShow.map { it.id }.chunked(BATCH).forEach { notes.setHidden(it, hidden = false) }
            log.info("${toHide.size} notes hidden from the lists and ${toShow.size} shown again in vault $vaultId")
            return wrong.size
        }

        /** [apply] for every vault: what a change of setting asks for. */
        suspend fun applyAll(): Int = vaults.all().sumOf { apply(it.id) }

        private suspend fun rules(
            vaultId: Long,
            reading: ReadingSettings,
        ): HiddenFiles {
            if (reading.showHiddenFiles) return HiddenFiles.NONE
            return HiddenFiles(
                ignoreFilters = ignoreFilters(vaultId),
                names = if (reading.hideAgentFiles) HiddenFiles.AGENT_FILES else emptySet(),
            )
        }

        private suspend fun ignoreFilters(vaultId: Long): List<String> {
            val text = files.readText(vaultId, VaultFilter.APP) ?: return emptyList()
            // A file Obsidian changes the shape of should cost the filter,
            // not the lists.
            return runCatching { HiddenFiles.ignoreFilters(text) }
                .onFailure { log.warn("could not read the excluded files of vault $vaultId: ${it.message}") }
                .getOrDefault(emptyList())
        }

        private fun ReadingSettings.sameHiding(other: ReadingSettings) =
            hideAgentFiles == other.hideAgentFiles && showHiddenFiles == other.showHiddenFiles

        private companion object {
            /** Well under SQLite's limit on the ids one statement may name. */
            const val BATCH = 500
        }
    }
