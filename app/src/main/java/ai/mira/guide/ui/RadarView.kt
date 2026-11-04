package ai.mira.guide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

data class RadarDot(
    val minor: Int,
    val title: String,
    val rssi: Int,
    val isCurrent: Boolean
)

/**
 * Proximity radar: you in the centre, beacons placed by signal strength.
 * Angle is stable per beacon (golden angle from minor) so dots do not jump.
 * Ring boundaries match the List view zones: Immediate -60, Near -85.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var dots: List<RadarDot> = emptyList()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#D8D2C4")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B6660")
        textSize = 26f
    }
    private val dotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1917")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    fun setData(newDots: List<RadarDot>) {
        dots = newDots
        invalidate()
    }

    private fun radiusFraction(rssi: Int): Float =
        (0.15f + ((-50f - rssi) / 45f) * 0.77f).coerceIn(0.12f, 0.95f)

    private fun zoneColor(rssi: Int): Int = when {
        rssi >= -60 -> Color.parseColor("#0F766E")
        rssi >= -85 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#A8A29E")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) - 44f
        if (maxR <= 0) return

        // Rings: Immediate / Near / Far. Labels sit on the left axis,
        // clear of the dots which favour the top and centre.
        val rings = listOf(0.33f to "Immediate", 0.66f to "Near", 1.0f to "Far")
        for ((frac, label) in rings) {
            canvas.drawCircle(cx, cy, maxR * frac, ringPaint)
            if (label == "Immediate") {
                // Inner ring: label at its top, clear of the centre dot.
                canvas.drawText(label, cx + 8f, cy - maxR * frac + 24f, labelPaint)
            } else {
                canvas.drawText(label, cx - maxR * frac + 8f, cy - 8f, labelPaint)
            }
        }

        // You
        fillPaint.color = Color.parseColor("#0B3B36")
        canvas.drawCircle(cx, cy, 16f, fillPaint)
        titlePaint.textSize = 24f
        canvas.drawText("YOU", cx, cy + 42f, titlePaint)

        for (dot in dots) {
            val angle = Math.toRadians((dot.minor * 137.508) % 360.0)
            val r = maxR * radiusFraction(dot.rssi)
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (dot.isCurrent) {
                fillPaint.color = Color.parseColor("#330F766E")
                canvas.drawCircle(x, y, 42f, fillPaint)
            }
            fillPaint.color = zoneColor(dot.rssi)
            canvas.drawCircle(x, y, if (dot.isCurrent) 30f else 24f, fillPaint)
            canvas.drawText(dot.minor.toString(), x, y + 9f, dotTextPaint)
            // The current beacon needs no label: it is the hero card above.
            if (!dot.isCurrent) {
                val short = if (dot.title.length > 16) dot.title.take(15) + "…" else dot.title
                val labelY = if (y >= cy) y + 58f else y - 38f
                canvas.drawText(short, x, labelY, titlePaint)
            }
        }
    }
}
