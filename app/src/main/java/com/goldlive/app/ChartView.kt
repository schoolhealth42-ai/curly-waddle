package com.goldlive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** مخطط خطي خفيف مرسوم يدوياً مع مؤشر لمس يعرض السعر والوقت */
class ChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var times: List<Long> = emptyList()
    private var values: List<Double> = emptyList()
    private var touchIndex = -1
    private var timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private var touchFmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)

    var valueFormatter: (Double) -> String = { String.format(Locale.US, "%.2f", it) }
    var emptyText: String = "جارٍ تحميل البيانات..."

    private val gold = ContextCompat.getColor(ctx, R.color.gold)
    private val textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
    private val gridColor = ContextCompat.getColor(ctx, R.color.divider)
    private val cardColor = ContextCompat.getColor(ctx, R.color.card)
    private val primaryText = ContextCompat.getColor(ctx, R.color.text_primary)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; strokeWidth = dp(2.2f); style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; textSize = dp(10.5f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; alpha = 70
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; strokeWidth = dp(1f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryText; textSize = dp(12f); isFakeBoldText = true
    }
    private val bubbleSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; textSize = dp(10.5f)
    }

    fun setData(t: List<Long>, v: List<Double>, range: String) {
        times = t; values = v; touchIndex = -1
        val ar = Locale("ar")
        timeFmt = when (range) {
            "1d" -> SimpleDateFormat("HH:mm", Locale.US)
            "5d" -> SimpleDateFormat("EEE", ar)
            else -> SimpleDateFormat("dd/MM", Locale.US)
        }
        touchFmt = when (range) {
            "1d" -> SimpleDateFormat("HH:mm", Locale.US)
            "5d" -> SimpleDateFormat("EEE HH:mm", ar)
            else -> SimpleDateFormat("dd/MM/yyyy", Locale.US)
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (values.size < 2) {
            labelPaint.textAlign = Paint.Align.CENTER
            c.drawText(emptyText, width / 2f, height / 2f, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }
        val left = dp(6f)
        val right = width - dp(62f)
        val top = dp(14f)
        val bottom = height - dp(22f)

        var min = values.minOrNull() ?: 0.0
        var max = values.maxOrNull() ?: 1.0
        if (abs(max - min) < 1e-9) { max += 1.0; min -= 1.0 }
        val pad = (max - min) * 0.08
        min -= pad; max += pad

        fun x(i: Int) = left + (right - left) * i / (values.size - 1).toFloat()
        fun y(v: Double) = (bottom - (bottom - top) * ((v - min) / (max - min))).toFloat()

        // شبكة وأرقام المحور
        labelPaint.textAlign = Paint.Align.LEFT
        for (k in 0..4) {
            val v = min + (max - min) * k / 4.0
            val yy = y(v)
            c.drawLine(left, yy, right, yy, gridPaint)
            c.drawText(valueFormatter(v), right + dp(6f), yy + dp(3.5f), labelPaint)
        }

        // المسار والتعبئة المتدرجة
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        val fill = Path(path)
        fill.lineTo(x(values.size - 1), bottom)
        fill.lineTo(x(0), bottom)
        fill.close()
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            Color.argb(110, Color.red(gold), Color.green(gold), Color.blue(gold)),
            Color.argb(0, Color.red(gold), Color.green(gold), Color.blue(gold)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(fill, fillPaint)
        c.drawPath(path, linePaint)

        // تسميات الزمن
        if (times.size == values.size) {
            val idx = listOf(0, values.size / 2, values.size - 1)
            idx.forEachIndexed { n, i ->
                labelPaint.textAlign = when (n) {
                    0 -> Paint.Align.LEFT; 1 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT
                }
                c.drawText(timeFmt.format(Date(times[i])), x(i), height - dp(6f), labelPaint)
            }
            labelPaint.textAlign = Paint.Align.LEFT
        }

        // آخر نقطة
        val lx = x(values.size - 1); val ly = y(values.last())
        c.drawCircle(lx, ly, dp(7f), ringPaint)
        c.drawCircle(lx, ly, dp(3.5f), dotPaint)

        // مؤشر اللمس
        if (touchIndex in values.indices) {
            val tx = x(touchIndex); val ty = y(values[touchIndex])
            c.drawLine(tx, top, tx, bottom, crossPaint)
            c.drawCircle(tx, ty, dp(5f), dotPaint)
            val main = valueFormatter(values[touchIndex])
            val sub = if (times.size == values.size) touchFmt.format(Date(times[touchIndex])) else ""
            val w = maxOf(bubbleText.measureText(main), bubbleSub.measureText(sub)) + dp(16f)
            val h = dp(40f)
            var bx = tx - w / 2
            bx = maxOf(left, minOf(bx, right - w))
            val by = if (ty - h - dp(10f) > top) ty - h - dp(10f) else ty + dp(10f)
            val r = RectF(bx, by, bx + w, by + h)
            c.drawRoundRect(r, dp(8f), dp(8f), bubblePaint)
            c.drawRoundRect(r, dp(8f), dp(8f), bubbleStroke)
            bubbleText.textAlign = Paint.Align.CENTER
            bubbleSub.textAlign = Paint.Align.CENTER
            c.drawText(main, r.centerX(), by + dp(17f), bubbleText)
            c.drawText(sub, r.centerX(), by + dp(32f), bubbleSub)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (values.size < 2) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = dp(6f); val right = width - dp(62f)
                val f = ((e.x - left) / (right - left)).coerceIn(0f, 1f)
                touchIndex = Math.round(f * (values.size - 1))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchIndex = -1
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
