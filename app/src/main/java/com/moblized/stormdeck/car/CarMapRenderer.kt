package com.moblized.stormdeck.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.LruCache
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.moblized.stormdeck.data.FrameSet
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.Http
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.Storm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Draws the radar map on the car's screen: a raster base map, the animated radar loop, labels on top,
 * your position and any tropical storms. Everything is plain tiles on a Canvas, so it is light on the phone.
 */
class CarMapRenderer : SurfaceCallback {
    private val thread = HandlerThread("car-map").apply { start() }
    private val render = Handler(thread.looper)
    private val loader = Executors.newFixedThreadPool(4)

    @Volatile private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var scale = 1f
    private var visible = Rect()

    @Volatile var center = LatLon(39.0, -96.0)
    @Volatile var zoom = 6.5
    @Volatile var dark = true
    @Volatile var me: LatLon? = null
    @Volatile var storms: List<Storm> = emptyList()
    @Volatile var frames: FrameSet? = null
        set(value) {
            field = value
            frameIndex = (value?.frames?.size ?: 1) - 1
            requestRender()
        }
    @Volatile var playing = true
        set(value) {
            field = value
            render.removeCallbacks(tick)
            if (value) render.postDelayed(tick, 600) else {
                frameIndex = (frames?.frames?.size ?: 1) - 1
                requestRender()
            }
        }

    @Volatile private var frameIndex = 0

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            val n = frames?.frames?.size ?: 0
            if (n > 1) {
                frameIndex = (frameIndex + 1) % n
                drawNow()
            }
            // Linger on the newest frame.
            render.postDelayed(this, if (frameIndex == n - 1) 1600 else 550)
        }
    }

    init {
        render.postDelayed(tick, 600)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        scale = (surfaceContainer.dpi / 160f).coerceIn(1f, 3f)
        if (visible.isEmpty) visible = Rect(0, 0, width, height)
        requestRender()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val world = worldPx()
        val cx = Geo.lonToX(center.lon, 0.0) / 256.0 * world + distanceX
        val cy = Geo.latToY(center.lat, 0.0) / 256.0 * world + distanceY
        center = LatLon(Geo.yToLat(cy / world * 256.0, 0.0), wrapLon(Geo.xToLon(cx / world * 256.0, 0.0)))
        requestRender()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // A double tap arrives as a negative factor.
        val dz = if (scaleFactor <= 0f) 1.0 else ln(scaleFactor.toDouble()) / ln(2.0)
        zoomBy(dz)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        onScroll(-velocityX * 0.08f, -velocityY * 0.08f)
    }

    fun zoomBy(dz: Double) {
        zoom = (zoom + dz).coerceIn(2.0, 12.0)
        requestRender()
    }

    fun recenter() {
        me?.let { center = it }
        requestRender()
    }

    fun release() {
        render.removeCallbacksAndMessages(null)
        thread.quitSafely()
        loader.shutdownNow()
        surface = null
    }

    fun requestRender() {
        render.removeCallbacks(drawRunnable)
        render.post(drawRunnable)
    }

    private val drawRunnable = Runnable { drawNow() }

    private fun worldPx(): Double = 256.0 * 2.0.pow(zoom) * scale

    private fun wrapLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0

    private fun drawNow() {
        val s = surface ?: return
        if (!s.isValid || width <= 0) return
        val canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            runCatching { s.lockCanvas(null) }.getOrNull()
        } ?: return
        try {
            draw(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 210 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    private fun draw(c: Canvas) {
        c.drawColor(if (dark) 0xFF0E1626.toInt() else 0xFFE8ECF1.toInt())
        val world = worldPx()
        val vis = if (visible.isEmpty) Rect(0, 0, width, height) else visible
        // World pixel of the screen's top-left corner, with the map centre in the middle of the visible area.
        val cx = Geo.lonToX(center.lon, 0.0) / 256.0 * world
        val cy = Geo.latToY(center.lat, 0.0) / 256.0 * world
        val ox = cx - vis.exactCenterX()
        val oy = cy - vis.exactCenterY()

        val style = if (dark) "dark" else "light"
        val baseZ = zoom.roundToInt().coerceIn(1, 18)
        layer(c, world, ox, oy, baseZ, tilePaint) { z, x, y -> "https://basemaps.cartocdn.com/${style}_nolabels/$z/$x/$y@2x.png" }

        val fs = frames
        if (fs != null && fs.frames.isNotEmpty()) {
            val idx = frameIndex.coerceIn(0, fs.frames.size - 1)
            val sizeShift = ln(fs.tileSize / 256.0) / ln(2.0)
            val rz = floor(zoom - sizeShift + 0.5).toInt().coerceIn(0, fs.maxZoom.toInt())
            // Load every frame of the loop for this view, but draw only the current one.
            fs.frames.forEachIndexed { i, f ->
                layer(c, world, ox, oy, rz, radarPaint, drawIt = i == idx) { z, x, y ->
                    f.url.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
                }
            }
        }

        layer(c, world, ox, oy, baseZ, tilePaint) { z, x, y -> "https://basemaps.cartocdn.com/${style}_only_labels/$z/$x/$y@2x.png" }

        storms.forEach { st ->
            val x = (Geo.lonToX(st.lon, 0.0) / 256.0 * world - ox).toFloat()
            val y = (Geo.latToY(st.lat, 0.0) / 256.0 * world - oy).toFloat()
            dot.style = Paint.Style.FILL
            dot.color = st.category.color.toInt()
            c.drawCircle(x, y, 9f * scale, dot)
            dot.style = Paint.Style.STROKE
            dot.strokeWidth = 2f * scale
            dot.color = Color.BLACK
            c.drawCircle(x, y, 9f * scale, dot)
            label(c, "${st.name} · ${st.category.short}", x + 13f * scale, y + 5f * scale)
        }

        me?.let { p ->
            val x = (Geo.lonToX(p.lon, 0.0) / 256.0 * world - ox).toFloat()
            val y = (Geo.latToY(p.lat, 0.0) / 256.0 * world - oy).toFloat()
            dot.style = Paint.Style.FILL
            dot.color = 0x5538BDF8
            c.drawCircle(x, y, 18f * scale, dot)
            dot.color = Color.WHITE
            c.drawCircle(x, y, 8f * scale, dot)
            dot.color = 0xFF0284C7.toInt()
            c.drawCircle(x, y, 6f * scale, dot)
        }

        if (fs != null && fs.frames.isNotEmpty()) {
            val f = fs.frames[frameIndex.coerceIn(0, fs.frames.size - 1)]
            val stamp = "Radar ${Fmt.localClock(f.timeMillis)}  ·  ${Fmt.ago(f.timeMillis)}"
            label(c, stamp, vis.left + 16f * scale, vis.bottom - 16f * scale, size = 15f)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float = 14f) {
        text.textSize = size * scale
        val w = text.measureText(s)
        val pad = 5f * scale
        dot.style = Paint.Style.FILL
        dot.color = if (dark) 0xCC0B1220.toInt() else 0xDDFFFFFF.toInt()
        c.drawRoundRect(RectF(x - pad, y - text.textSize, x + w + pad, y + pad), 6f * scale, 6f * scale, dot)
        text.color = if (dark) Color.WHITE else 0xFF0F172A.toInt()
        c.drawText(s, x, y, text)
    }

    /** Draws (and loads) the tiles of one layer at tile zoom [z] covering the screen. */
    private inline fun layer(
        c: Canvas,
        world: Double,
        ox: Double,
        oy: Double,
        z: Int,
        paint: Paint,
        drawIt: Boolean = true,
        crossinline url: (Int, Int, Int) -> String,
    ) {
        val n = 1 shl z
        val tile = world / n
        val x0 = floor(ox / tile).toInt()
        val y0 = max(0, floor(oy / tile).toInt())
        val x1 = floor((ox + width) / tile).toInt()
        val y1 = min(n - 1, floor((oy + height) / tile).toInt())
        val dst = RectF()
        for (ty in y0..y1) for (tx in x0..x1) {
            val wx = ((tx % n) + n) % n
            val u = url(z, wx, ty)
            val bmp = cache.get(u)
            if (bmp == null) {
                fetch(u)
                continue
            }
            if (!drawIt) continue
            val left = (tx * tile - ox).toFloat()
            val top = (ty * tile - oy).toFloat()
            dst.set(left, top, (left + tile).toFloat() + 0.5f, (top + tile).toFloat() + 0.5f)
            c.drawBitmap(bmp, null, dst, paint)
        }
    }

    private fun fetch(url: String) {
        if (!pending.add(url)) return
        val lastFail = failed[url]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 60_000) {
            pending.remove(url)
            return
        }
        runCatching {
            loader.execute {
                val bytes = Http.bytesBlocking(url)
                val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                if (bmp != null) cache.put(url, bmp) else failed[url] = System.currentTimeMillis()
                pending.remove(url)
                requestRender()
            }
        }.onFailure { pending.remove(url) }
    }
}
