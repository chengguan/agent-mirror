package dev.chengguan.mirror.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

private fun headerStroke(min: Float) = Stroke(
    width = min * 0.09f,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

@Composable
fun SettingsGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val stroke = headerStroke(size.minDimension)
        val c = center
        val inner = size.minDimension * 0.24f
        val crown = size.minDimension * 0.42f
        val hole = size.minDimension * 0.13f
        val teeth = 6
        val path = Path()
        for (i in 0 until teeth) {
            val base = (kotlin.math.PI * 2.0 * i / teeth - kotlin.math.PI / 2.0)
            val a0 = (base - kotlin.math.PI / teeth * 0.32).toFloat()
            val a1 = (base - kotlin.math.PI / teeth * 0.14).toFloat()
            val a2 = (base + kotlin.math.PI / teeth * 0.14).toFloat()
            val a3 = (base + kotlin.math.PI / teeth * 0.32).toFloat()
            fun pt(angle: Float, r: Float) = Offset(c.x + cos(angle) * r, c.y + sin(angle) * r)
            val start = pt(a0, inner)
            if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            path.lineTo(pt(a1, crown).x, pt(a1, crown).y)
            path.lineTo(pt(a2, crown).x, pt(a2, crown).y)
            path.lineTo(pt(a3, inner).x, pt(a3, inner).y)
        }
        path.close()
        drawPath(path, color = tint, style = stroke)
        drawCircle(color = tint, radius = hole, center = c, style = stroke)
    }
}

@Composable
fun DisconnectGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = headerStroke(w)
        val boxW = w * 0.42f
        val boxH = w * 0.70f
        val left = (w - boxW - w * 0.32f) / 2f
        val top = (w - boxH) / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(boxW, boxH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            style = stroke,
        )
        val midY = top + boxH / 2f
        val shaftStart = left + boxW + w * 0.06f
        val tip = left + boxW + w * 0.32f
        drawLine(tint, Offset(shaftStart, midY), Offset(tip, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(tip - w * 0.14f, midY - w * 0.14f), Offset(tip, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(tip - w * 0.14f, midY + w * 0.14f), Offset(tip, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
