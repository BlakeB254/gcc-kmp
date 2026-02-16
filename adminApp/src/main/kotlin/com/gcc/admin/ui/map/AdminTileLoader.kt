package com.gcc.admin.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.*

/**
 * Downloads and caches OpenStreetMap raster tiles for the admin Canvas map.
 *
 * Tiles are 256x256 PNG images fetched from the OSM tile server.
 * A global in-memory cache avoids re-downloading tiles that have already been
 * loaded (shared across all AdminMap instances).
 *
 * This is a copy of the composeApp TileLoader adapted for the admin package.
 */
object AdminTileLoader {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** Returns a cached tile, or null if not yet loaded. */
    fun getCachedTile(zoom: Int, x: Int, y: Int): ImageBitmap? {
        return cache["$zoom/$x/$y"]
    }

    /** Check if a tile is in the cache (even if null/failed). */
    fun isCached(zoom: Int, x: Int, y: Int): Boolean {
        return cache.containsKey("$zoom/$x/$y")
    }

    /**
     * Download and cache a tile. Returns the ImageBitmap or null on failure.
     * Safe to call from multiple coroutines — duplicate downloads are prevented.
     */
    suspend fun loadTile(zoom: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$zoom/$x/$y"
        cache[key]?.let { return it }
        if (!loading.add(key)) return null // already being loaded

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
                val conn = url.openConnection()
                conn.setRequestProperty("User-Agent", "GCC-Admin-App/1.0 (demo; contact: dev@globalcommunitycleaners.com)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val img = ImageIO.read(conn.getInputStream())
                val bitmap = img?.toComposeImageBitmap()
                cache[key] = bitmap
                bitmap
            } catch (_: Exception) {
                cache[key] = null // mark as failed so we don't retry endlessly
                null
            } finally {
                loading.remove(key)
            }
        }
    }

    // --- Tile coordinate math (standard Web Mercator / Slippy Map) ---

    /** Convert longitude to tile X coordinate at given zoom. */
    fun tileX(lng: Double, zoom: Int): Int {
        return floor((lng + 180.0) / 360.0 * (1 shl zoom).toDouble()).toInt()
    }

    /** Convert latitude to tile Y coordinate at given zoom. */
    fun tileY(lat: Double, zoom: Int): Int {
        val latRad = lat * PI / 180.0
        return floor(
            (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom).toDouble()
        ).toInt()
    }

    /** Convert tile X coordinate to longitude (west edge of tile). */
    fun tileLng(x: Int, zoom: Int): Double {
        return x.toDouble() / (1 shl zoom).toDouble() * 360.0 - 180.0
    }

    /** Convert tile Y coordinate to latitude (north edge of tile). */
    fun tileLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y.toDouble() / (1 shl zoom).toDouble()
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }
}
