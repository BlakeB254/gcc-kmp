package com.gcc.admin.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

object AdminTileLoader {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun getCachedTile(zoom: Int, x: Int, y: Int): ImageBitmap? = cache["$zoom/$x/$y"]

    fun isCached(zoom: Int, x: Int, y: Int): Boolean = cache.containsKey("$zoom/$x/$y")

    suspend fun loadTile(zoom: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$zoom/$x/$y"
        cache[key]?.let { return it }
        if (!loading.add(key)) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://basemaps.cartocdn.com/dark_all/$zoom/$x/$y@2x.png")
                val conn = url.openConnection()
                conn.setRequestProperty("User-Agent", "GCC-Admin-App/1.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val img = ImageIO.read(conn.getInputStream())
                val bitmap = img?.toComposeImageBitmap()
                cache[key] = bitmap
                bitmap
            } catch (_: Exception) {
                cache[key] = null
                null
            } finally {
                loading.remove(key)
            }
        }
    }
}
