package io.github.marcomow.yuvomi.feature.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The device side of documents: reading a picked file, saving one where the member chooses,
 * handing one to another app, and drawing images and PDF pages. No WebView: images decode
 * with `BitmapFactory`, PDFs render with the platform's `PdfRenderer`.
 */
object DocumentFiles {
    /** The FileProvider authority the manifest declares (docs/spec/documents.md, DOCS-R24). */
    fun authority(context: Context) = "${context.packageName}.files"

    private const val SHARED_DIR = "documents-open"

    /** `readFileAsDataUrl`'s input: name, type and bytes of a file the system picker returned. */
    suspend fun read(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            PickedFile(fileName, mime, bytes)
        }.getOrNull()
    }

    /** Writes [bytes] to a document the member created with the system's "save as". */
    suspend fun save(context: Context, uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null }.getOrDefault(false)
    }

    /** The name a downloaded file gets (`original_name`, slashes replaced like the server's filename). */
    fun fileName(doc: Document): String = (doc.originalName?.takeIf { it.isNotBlank() } ?: "${doc.id}").replace(Regex("[/\\\\]"), "_")

    fun mime(doc: Document): String = doc.mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

    enum class Handoff { OPENED, NO_APP, UNAVAILABLE }

    /**
     * "Open with": a private copy under the cache, offered through the FileProvider to an app
     * the member picks. The previous copy is removed first, so at most one document stays on
     * the device after the viewer closes (the server sends `no-store` for the same reason).
     */
    suspend fun handOff(context: Context, doc: Document, bytes: ByteArray, share: Boolean): Handoff {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, SHARED_DIR).apply { deleteRecursively(); mkdirs() }
                val file = File(dir, fileName(doc)).apply { writeBytes(bytes) }
                FileProvider.getUriForFile(context, authority(context), file)
            }.getOrNull()
        } ?: return Handoff.UNAVAILABLE
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType(mime(doc)).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, doc.name)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(doc))
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, doc.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Handoff.OPENED
        } catch (e: ActivityNotFoundException) {
            Handoff.NO_APP
        }
    }

    /** Forgets the copy "Open with" left behind. */
    fun clearHandoff(context: Context) {
        runCatching { File(context.cacheDir, SHARED_DIR).deleteRecursively() }
    }

    /** An image, scaled down by powers of two until its long side fits [maxPx]. */
    fun decodeImage(bytes: ByteArray, maxPx: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    /** A PDF opened for drawing its pages; [close] releases renderer and temporary file. */
    class Pdf private constructor(private val file: File, private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
        val pageCount: Int get() = renderer.pageCount

        /** One page at [widthPx], on white like paper. PdfRenderer draws one page at a time. */
        fun render(index: Int, widthPx: Int): ImageBitmap? = synchronized(this) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val width = widthPx.coerceIn(64, 2048)
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(64, 4096)
                    val bitmap = createBitmap(width, height)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }.getOrNull()
        }

        fun close() = synchronized(this) {
            runCatching { renderer.close() }
            runCatching { fd.close() }
            file.delete()
        }

        companion object {
            fun open(context: Context, bytes: ByteArray): Pdf? = runCatching {
                val file = File.createTempFile("doc", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    Pdf(file, fd, PdfRenderer(fd))
                } catch (e: Exception) {
                    fd.close()
                    file.delete()
                    throw e
                }
            }.getOrNull()
        }
    }
}

/**
 * List previews (`createDocumentThumbs` in utils/document-thumbs.js): local images and the
 * first page of local PDFs up to 10 MB, and the DMS thumbnail of Paperless documents. At most
 * two load at once; a picture is kept per `id:updated_at`, so a replaced file gets a new one;
 * a failure is remembered and the category icon stays.
 */
class DocumentThumbs(private val context: Context, private val load: suspend (Document) -> ByteArray?) {
    private val cache = LruCache<String, ImageBitmap>(40)
    private val failed = HashSet<String>()
    private val gate = Semaphore(2)

    fun key(doc: Document) = "${doc.id}:${doc.updatedAt.orEmpty()}"

    fun cached(doc: Document): ImageBitmap? = cache.get(key(doc))

    fun wants(doc: Document): Boolean = DocumentRules.thumbKind(doc) != null || DocumentRules.hasDmsThumbnail(doc)

    suspend fun get(doc: Document, px: Int): ImageBitmap? {
        val key = key(doc)
        cache.get(key)?.let { return it }
        if (key in failed || !wants(doc)) return null
        val image = gate.withPermit {
            val bytes = load(doc) ?: return@withPermit null
            withContext(Dispatchers.Default) {
                if (DocumentRules.thumbKind(doc) == DocumentRules.PreviewKind.PDF) {
                    DocumentFiles.Pdf.open(context, bytes)?.let { pdf ->
                        try {
                            if (pdf.pageCount > 0) pdf.render(0, px) else null
                        } finally {
                            pdf.close()
                        }
                    }
                } else {
                    DocumentFiles.decodeImage(bytes, px)
                }
            }
        }
        if (image == null) synchronized(failed) { failed += key } else cache.put(key, image)
        return image
    }
}
