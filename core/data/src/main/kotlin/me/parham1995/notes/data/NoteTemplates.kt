package me.parham1995.notes.data

import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.obsidian.Templates
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** A template a new note can start from. */
data class TemplateFile(
    val path: String,
    /** Its name, with the templates folder and `.md` taken off. */
    val name: String,
)

/**
 * New notes from the vault's templates, the way Obsidian's core Templates
 * plugin makes them: the folder and the date and time formats are the
 * vault's own, from `.obsidian/templates.json`.
 */
@Singleton
class NoteTemplates
    @Inject
    constructor(
        private val configs: ObsidianConfigStore,
        private val notes: NoteDao,
        private val files: VaultFileStore,
        private val writes: VaultWriteRepository,
    ) {
        /** The templates [vaultId] has, by name; none when it has no templates folder. */
        suspend fun list(vaultId: Long): List<TemplateFile> {
            val templates = configs.templates(vaultId)
            return notes
                .allIds(vaultId)
                .map { it.path }
                .filter(templates::holds)
                .map { TemplateFile(it, it.removePrefix("${templates.folder}/").removeSuffix(".md")) }
                .sortedBy { it.name.lowercase() }
        }

        /**
         * Makes `[folder]/[title].md` from [template], its variables filled.
         * The path is handed back with the result, to open the note once it
         * is there.
         */
        suspend fun create(
            vaultId: Long,
            folder: String,
            template: String,
            title: String,
            at: LocalDateTime = LocalDateTime.now(),
        ): Pair<WriteResult, String?> {
            val name = Templates.fileName(title)
            if (name.isEmpty()) return WriteResult.Refused("give the note a title") to null
            val source =
                files.readText(vaultId, template) ?: return WriteResult.Refused("that template is gone") to null
            val dir = folder.trim('/')
            val path = if (dir.isEmpty()) "$name.md" else "$dir/$name.md"
            val text = configs.templates(vaultId).fill(source, name, at)
            return writes.createNote(vaultId, path, text) to path
        }
    }
