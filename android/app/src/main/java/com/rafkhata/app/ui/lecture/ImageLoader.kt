package com.rafkhata.app.ui.lecture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.rafkhata.app.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tiny image loader for board photos (presigned URLs), with an in-memory cache. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(client: OkHttpClient, url: String, maxPx: Int): Bitmap? {
        val key = "$maxPx|${url.substringBefore('?')}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}

@Composable
fun RemoteImage(url: String, maxPx: Int, contentDescription: String?, modifier: Modifier = Modifier) {
    val client = LocalContext.current.container.uploadClient
    val bitmap by produceState<Bitmap?>(initialValue = null, url, maxPx) { value = ImageLoader.load(client, url, maxPx) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current == null) {
            CircularProgressIndicator()
        } else {
            Image(current.asImageBitmap(), contentDescription = contentDescription, contentScale = ContentScale.Fit)
        }
    }
}
