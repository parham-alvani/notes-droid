package me.parham1995.notes.data

import android.database.Cursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import dagger.hilt.android.EntryPointAccessors
import java.io.FileNotFoundException

/**
 * Puts the vaults in the system file picker, so any app's upload or attach
 * button can reach a note. Everything it answers comes from [VaultDocuments].
 *
 * Android creates content providers before `Application.onCreate`, which is
 * before Hilt's component exists -- so the dependency is looked up on first
 * use, never in [onCreate].
 */
class VaultDocumentsProvider : DocumentsProvider() {
    private val documents: VaultDocuments by lazy {
        EntryPointAccessors
            .fromApplication(requireNotNull(context).applicationContext, VaultDocuments.Access::class.java)
            .documents()
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = documents.roots(projection)

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = documents.document(documentId, projection)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = documents.children(parentDocumentId, projection)

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = documents.search(rootId, query, projection)

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = documents.isChild(parentDocumentId, documentId)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // Nothing is advertised as writable, but the mode is the caller's to
        // ask for; a read-only vault answers anything else with no.
        if (mode != "r") throw FileNotFoundException("read-only: $documentId")
        return ParcelFileDescriptor.open(documents.open(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
