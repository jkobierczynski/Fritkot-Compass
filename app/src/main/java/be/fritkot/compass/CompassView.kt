// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * A simple compass dial. The N/E/S/W ring rotates with the device's
 * heading (so it behaves like a normal compass), and the gold arrow
 * always points toward [targetBearingDegrees] — the direction of the
 * nearest fritkot — regardless of how the phone is held.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** True-north bearing (0-360) that the phone is currently facing. */
    var deviceHeadingDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** True-north bearing (0-360) from the user to the target fritkot. */
    var targetBearingDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** When false, we have no heading sensor / no fix yet — draw a muted placeholder. */
    var hasHeading: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#5A4522")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CBB891")
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2B705")
    }

    private val arrowTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8C6A1F")
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF6E5")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - ringPaint.strokeWidth

        // Outer ring
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Rotating N/E/S/W dial, following the device heading
        canvas.save()
        canvas.rotate(-deviceHeadingDegrees, cx, cy)
        drawTick(canvas, cx, cy, radius, 0f, "N")
        drawTick(canvas, cx, cy, radius, 90f, "E")
        drawTick(canvas, cx, cy, radius, 180f, "S")
        drawTick(canvas, cx, cy, radius, 270f, "W")
        canvas.restore()

        // Arrow pointing at the target fritkot
        val arrowRotation = targetBearingDegrees - deviceHeadingDegrees
        canvas.save()
        canvas.rotate(arrowRotation, cx, cy)
        drawArrow(canvas, cx, cy, radius)
        canvas.restore()

        canvas.drawCircle(cx, cy, 10f, centerDotPaint)
    }

    private fun drawTick(canvas: Canvas, cx: Float, cy: Float, radius: Float, angleDeg: Float, label: String) {
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        val textY = cy - radius + 40f
        canvas.drawText(label, cx, textY, tickPaint)
        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val headLen = radius * 0.62f
        val tailLen = radius * 0.38f
        val halfWidth = radius * 0.10f

        val headPaint = if (hasHeading) arrowFillPaint else arrowFillPaint.apply { alpha = 130 }
        val tailPaint = if (hasHeading) arrowTailPaint else arrowTailPaint.apply { alpha = 130 }

        // Head (pointing "up" before rotation is applied by the canvas)
        val head = Path().apply {
            moveTo(cx, cy - headLen)
            lineTo(cx - halfWidth, cy)
            lineTo(cx + halfWidth, cy)
            close()
        }
        canvas.drawPath(head, headPaint)

        // Tail
        val tail = Path().apply {
            moveTo(cx, cy + tailLen)
            lineTo(cx - halfWidth * 0.6f, cy)
            lineTo(cx + halfWidth * 0.6f, cy)
            close()
        }
        canvas.drawPath(tail, tailPaint)

        // reset alpha for next draw call
        arrowFillPaint.alpha = 255
        arrowTailPaint.alpha = 255
    }
}
