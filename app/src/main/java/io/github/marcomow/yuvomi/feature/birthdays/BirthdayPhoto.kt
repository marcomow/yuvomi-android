package io.github.marcomow.yuvomi.feature.birthdays

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * A picked picture as the data URL `photo_data` takes (`pickCroppedImage` in the web app crops
 * it square): PNG, JPEG or WebP, centre-cropped, scaled to [OUTPUT] pixels, sent as JPEG.
 */
object BirthdayPhoto {
    private val ACCEPTED = setOf("image/png", "image/jpeg", "image/webp")
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    private const val OUTPUT = 512

    sealed interface Result {
        data class Ok(val dataUrl: String) : Result
        data class Problem(val key: String) : Result
    }

    fun encode(context: Context, uri: Uri): Result {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in ACCEPTED) return Result.Problem("android.birthdays.photoType")
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return Result.Problem("common.errorGeneric")
        if (bytes.size > MAX_FILE_BYTES) return Result.Problem("android.birthdays.photoTooLarge")
        val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return Result.Problem("common.errorGeneric")
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = if (side > OUTPUT) square.scale(OUTPUT, OUTPUT) else square
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Result.Ok("data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
