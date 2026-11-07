package com.anthonyla.paperize.data.source

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.anthonyla.paperize.core.util.GooglePhotosAlbumScraper
import com.anthonyla.paperize.core.util.isDocumentMissing
import com.anthonyla.paperize.core.util.scanFolderImages
import com.anthonyla.paperize.domain.source.DocumentSource
import com.anthonyla.paperize.domain.source.SourceFolder
import com.anthonyla.paperize.domain.source.SourceImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AndroidDocumentSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) : DocumentSource {
    override suspend fun retainReadPermission(uri: String): Unit = withContext(Dispatchers.IO) {
        if (GooglePhotosAlbumScraper.isRemoteUrl(uri)) return@withContext
        try {
            context.contentResolver.takePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Some CloudMediaProvider / Photo Picker URIs grant read access without supporting
            // persistable URI grants; log and allow readImage() to proceed.
            Log.w("AndroidDocumentSource", "Persistable permission not supported for $uri", e)
        }
    }

    override suspend fun readImage(uri: String): SourceImage = withContext(Dispatchers.IO) {
        if (GooglePhotosAlbumScraper.isRemoteUrl(uri)) {
            val name = uri.substringAfterLast('/').substringBefore('=')
            return@withContext SourceImage(uri, "$name.jpg", System.currentTimeMillis())
        }
        val parsedUri = uri.toUri()
        // Query OpenableColumns first (works reliably with Android Photo Picker / CloudMediaProvider)
        var displayName: String? = null
        runCatching {
            context.contentResolver.query(
                parsedUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(0)
                }
            }
        }
        val document = DocumentFile.fromSingleUri(context, parsedUri)
        SourceImage(
            uri = uri,
            name = displayName ?: document?.name ?: uri.substringAfterLast('/'),
            modifiedAt = document?.lastModified()?.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    override suspend fun readFolder(uri: String, onProgress: (Int) -> Unit): SourceFolder = withContext(Dispatchers.IO) {
        if (GooglePhotosAlbumScraper.isGooglePhotosUrl(uri) || GooglePhotosAlbumScraper.isRemoteUrl(uri)) {
            return@withContext GooglePhotosAlbumScraper.scrapeAlbum(uri, onProgress)
        }
        val tree = uri.toUri()
        val images = tree.scanFolderImages(context, onProgress).map {
            SourceImage(it.uri.toString(), it.name, it.lastModified)
        }
        SourceFolder(DocumentFile.fromTreeUri(context, tree)?.name ?: uri.substringAfterLast('/'), images)
    }

    override suspend fun isMissing(uri: String, isTree: Boolean): Boolean = withContext(Dispatchers.IO) {
        // Remote HTTP/HTTPS URLs are synced via RefreshFolderUseCase; don't prune them offline
        if (GooglePhotosAlbumScraper.isRemoteUrl(uri)) return@withContext false
        val source = uri.toUri()
        val document = if (isTree) {
            DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getTreeDocumentId(source))
        } else source
        document.isDocumentMissing(context.contentResolver)
    }
}