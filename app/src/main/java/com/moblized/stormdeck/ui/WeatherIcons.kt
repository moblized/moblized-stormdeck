package com.moblized.stormdeck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.moblized.stormdeck.data.Sky
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's weather icons, drawn in code so the same picture works in the app, the widget and the car.
 * Coordinates are fractions of the icon's size.
 */
object WeatherIcons {
    private const val SUN = 0xFFFBBF24.toInt()
    private const val MOON = 0xFFE2E8F0.toInt()
    private const val CLOUD = 0xFFDCE3EC.toInt()
    private const val CLOUD_EDGE = 0xFF94A3B8.toInt()
    private const val STORM_CLOUD = 0xFF64748B.toInt()
    private const val RAIN = 0xFF38BDF8.toInt()
    private const val SNOW = 0xFFFFFFFF.toInt()
    private const val ICE = 0xFF7DD3FC.toInt()
    private const val BOLT = 0xFFFACC15.toInt()
    private const val FOG = 0xFFB6C2D1.toInt()

    fun bitmap(sky: Sky, day: Boolean, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), sky, day, px.toFloat())
        return b
    }

    fun draw(c: Canvas, sky: Sky, day: Boolean, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (sky) {
            Sky.Clear -> if (day) sun(c, p, size, 0.5f, 0.5f, 0.2f) else moon(c, p, size, 0.5f, 0.5f, 0.27f)
            Sky.PartlyCloudy -> {
                if (day) sun(c, p, size, 0.37f, 0.36f, 0.15f) else moon(c, p, size, 0.36f, 0.36f, 0.19f)
                cloud(c, p, size, 0.08f, 0.12f, 0.9f, CLOUD)
            }
            Sky.Cloudy -> {
                cloud(c, p, size, -0.12f, -0.06f, 0.75f, CLOUD_EDGE)
                cloud(c, p, size, 0.05f, 0.06f, 0.95f, CLOUD)
            }
            Sky.Fog -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD)
                p.color = FOG
                p.strokeWidth = size * 0.055f
                p.strokeCap = Paint.Cap.ROUND
                p.style = Paint.Style.STROKE
                listOf(0.66f, 0.77f, 0.88f).forEachIndexed { i, y ->
                    val inset = if (i % 2 == 0) 0.14f else 0.22f
                    c.drawLine(size * inset, size * y, size * (1 - inset), size * y, p)
                }
            }
            Sky.Drizzle -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD)
                p.color = RAIN
                listOf(0.32f to 0.75f, 0.5f to 0.82f, 0.68f to 0.75f, 0.41f to 0.9f, 0.59f to 0.9f).forEach { (x, y) ->
                    c.drawCircle(size * x, size * y, size * 0.035f, p)
                }
            }
            Sky.Rain -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD)
                drops(c, p, size, listOf(0.34f, 0.52f, 0.7f), RAIN)
            }
            Sky.HeavyRain -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD_EDGE)
                drops(c, p, size, listOf(0.28f, 0.42f, 0.56f, 0.7f), RAIN)
            }
            Sky.Freezing -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD)
                drops(c, p, size, listOf(0.36f, 0.64f), ICE)
                flake(c, p, size, 0.5f, 0.84f, 0.07f, SNOW)
            }
            Sky.Snow -> {
                cloud(c, p, size, 0f, -0.1f, 0.9f, CLOUD)
                flake(c, p, size, 0.33f, 0.78f, 0.07f, SNOW)
                flake(c, p, size, 0.67f, 0.78f, 0.07f, SNOW)
                flake(c, p, size, 0.5f, 0.9f, 0.065f, SNOW)
            }
            Sky.Thunder -> {
                cloud(c, p, size, 0f, -0.12f, 0.9f, STORM_CLOUD)
                drops(c, p, size, listOf(0.3f, 0.72f), RAIN)
                bolt(c, p, size)
            }
        }
    }

    private fun sun(c: Canvas, p: Paint, s: Float, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL
        p.color = SUN
        c.drawCircle(s * cx, s * cy, s * r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * r * 0.28f
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            val r1 = r * 1.45f
            val r2 = r * 1.95f
            c.drawLine(
                s * (cx + r1 * cos(a).toFloat()), s * (cy + r1 * sin(a).toFloat()),
                s * (cx + r2 * cos(a).toFloat()), s * (cy + r2 * sin(a).toFloat()), p,
            )
        }
        p.style = Paint.Style.FILL
    }

    private fun moon(c: Canvas, p: Paint, s: Float, cx: Float, cy: Float, r: Float) {
        val full = Path().apply { addCircle(s * cx, s * cy, s * r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(s * (cx + r * 0.55f), s * (cy - r * 0.35f), s * r * 0.85f, Path.Direction.CW) }
        full.op(bite, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL
        p.color = MOON
        c.drawPath(full, p)
    }

    /** A cloud filling roughly the lower 2/3 of the box, shifted by (dx, dy) and scaled by k. */
    private fun cloud(c: Canvas, p: Paint, s: Float, dx: Float, dy: Float, k: Float, color: Int) {
        fun x(v: Float) = s * (0.5f + (v - 0.5f) * k + dx)
        fun y(v: Float) = s * (0.5f + (v - 0.5f) * k + dy)
        val path = Path()
        path.addCircle(x(0.36f), y(0.58f), s * 0.16f * k, Path.Direction.CW)
        path.addCircle(x(0.54f), y(0.47f), s * 0.21f * k, Path.Direction.CW)
        path.addCircle(x(0.72f), y(0.6f), s * 0.14f * k, Path.Direction.CW)
        path.addRoundRect(RectF(x(0.2f), y(0.56f), x(0.86f), y(0.74f)), s * 0.09f * k, s * 0.09f * k, Path.Direction.CW)
        p.style = Paint.Style.FILL
        p.color = color
        c.drawPath(path, p)
    }

    private fun drops(c: Canvas, p: Paint, s: Float, xs: List<Float>, color: Int) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.05f
        xs.forEachIndexed { i, x ->
            val top = if (i % 2 == 0) 0.72f else 0.77f
            c.drawLine(s * x, s * top, s * (x - 0.05f), s * (top + 0.14f), p)
        }
        p.style = Paint.Style.FILL
    }

    private fun flake(c: Canvas, p: Paint, s: Float, cx: Float, cy: Float, r: Float, color: Int) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.03f
        for (i in 0 until 3) {
            val a = Math.PI / 3 * i + Math.PI / 2
            c.drawLine(
                s * (cx - r * cos(a).toFloat()), s * (cy - r * sin(a).toFloat()),
                s * (cx + r * cos(a).toFloat()), s * (cy + r * sin(a).toFloat()), p,
            )
        }
        p.style = Paint.Style.FILL
    }

    private fun bolt(c: Canvas, p: Paint, s: Float) {
        val path = Path().apply {
            moveTo(s * 0.55f, s * 0.6f)
            lineTo(s * 0.42f, s * 0.8f)
            lineTo(s * 0.51f, s * 0.8f)
            lineTo(s * 0.45f, s * 0.97f)
            lineTo(s * 0.63f, s * 0.73f)
            lineTo(s * 0.54f, s * 0.73f)
            lineTo(s * 0.6f, s * 0.6f)
            close()
        }
        p.style = Paint.Style.FILL
        p.color = BOLT
        c.drawPath(path, p)
        p.color = Color.TRANSPARENT
    }
}
