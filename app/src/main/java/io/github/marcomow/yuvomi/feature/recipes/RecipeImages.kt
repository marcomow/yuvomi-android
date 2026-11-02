package io.github.marcomow.yuvomi.feature.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Recipe pictures (utils/recipe-thumb.js): fetched as bytes from the household's server with
 * the session (`YuvomiClient.bytes`, same host only), decoded with `BitmapFactory`, kept in
 * memory by path. A failed load is remembered and falls back to the placeholder.
 */
class RecipeImages(private val client: () -> YuvomiClient?) {
    private val cache = LruCache<String, ImageBitmap>(48)
    private val failed = HashSet<String>()

    fun cached(path: String): ImageBitmap? = cache.get(path)

    suspend fun load(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        if (path in failed) return null
        val bytes = runCatching { client()?.bytes(path) }.getOrNull()
        val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        if (bitmap == null) failed += path else cache.put(path, bitmap)
        return bitmap
    }

    /** After a save the stored picture may have changed under the same path. */
    fun forget(path: String) {
        cache.remove(path)
        failed -= path
    }
}

/** `recipeThumbEl`: the picture, or the utensils placeholder when there is none or it fails. */
@Composable
fun RecipeThumb(images: RecipeImages, path: String?, size: Dp = 40.dp) {
    val bitmap by produceState(path?.let(images::cached), path) { value = path?.let { images.load(it) } }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(size).clip(shape).background(Yuvomi.colors.module("recipes").copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Outlined.Restaurant, null, tint = Yuvomi.colors.module("recipes"), modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * The own picture as the head of the detail (`recipeHeroEl`); nothing at all when there is
 * none or it fails to load: no placeholder block.
 */
@Composable
fun RecipeHero(images: RecipeImages, path: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState(path?.let(images::cached), path) { value = path?.let { images.load(it) } }
    val b = bitmap ?: return
    Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(16.dp)))
}

/**
 * `pickCroppedImage` for a recipe: PNG, JPEG or WebP up to 5 MB, cut to the centre square
 * and stored as a 256 px JPEG data URL, as upstream's crop dialog returns it. Upstream lets
 * the member move the square; the app takes the centre.
 */
object RecipeImageEncoder {
    private val ACCEPTED = setOf("image/png", "image/jpeg", "image/webp")
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private const val MAX_DATA_LENGTH = 768 * 1024
    private const val OUTPUT = 256

    sealed interface Result {
        data class Ok(val dataUrl: String) : Result
        data class Problem(val key: String) : Result
    }

    fun encode(context: Context, uri: Uri): Result {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in ACCEPTED) return Result.Problem("android.recipes.imageType")
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return Result.Problem("android.recipes.imageRead")
        if (bytes.size > MAX_FILE_BYTES) return Result.Problem("android.recipes.imageFileTooLarge")
        val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return Result.Problem("android.recipes.imageRead")
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = square.scale(OUTPUT, OUTPUT)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return if (dataUrl.length > MAX_DATA_LENGTH) Result.Problem("recipes.imageTooLarge") else Result.Ok(dataUrl)
    }

    /** A picked picture shown in the form before it is saved. */
    fun preview(dataUrl: String): ImageBitmap? = runCatching {
        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
