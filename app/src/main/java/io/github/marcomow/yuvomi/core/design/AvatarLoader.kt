package io.github.marcomow.yuvomi.core.design

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.marcomow.yuvomi.core.api.YuvomiClient

/**
 * SIGNIN-R18: a relative `avatar_data` is fetched from the household's server
 * with the session cookie and cached by URL (its `v=` key changes with the
 * picture); a `data:` URL is decoded.
 */
class AvatarLoader(private val client: () -> YuvomiClient?) {
    private val cache = LruCache<String, ImageBitmap>(64)
    private val failed = HashSet<String>()

    fun cached(source: String): ImageBitmap? = cache.get(source)

    suspend fun load(source: String): ImageBitmap? {
        cache.get(source)?.let { return it }
        if (source in failed) return null
        val bytes = runCatching {
            if (source.startsWith("data:")) {
                Base64.decode(source.substringAfter(','), Base64.DEFAULT)
            } else {
                client()?.bytes(source)
            }
        }.getOrNull()
        val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        if (bitmap == null) failed += source else cache.put(source, bitmap)
        return bitmap
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}
