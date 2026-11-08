package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * R133 · 播放控制的全部图标，一律用 Canvas 画出来，不再拿「‹ ⏸ ▶ › ×」字符凑数。
 * 纯矢量、随主题取色、任意尺寸不糊；不引第三方图标库，不加资源文件。
 */

@Composable
internal fun IconPlayPause(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (!playing) {
            val p = Path().apply {
                moveTo(w * 0.32f, h * 0.18f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.32f, h * 0.82f)
                close()
            }
            drawPath(p, tint)
        } else {
            val sw = (w * 0.13f).coerceAtLeast(2.dp.toPx())
            listOf(0.36f, 0.64f).forEach { x ->
                drawLine(tint, Offset(w * x, h * 0.22f), Offset(w * x, h * 0.78f),
                    sw, StrokeCap.Round)
            }
        }
    }
}

/** 上一首 / 下一首：两个三角 + 一条封口竖线。 */
@Composable
internal fun IconSkip(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val dir = if (forward) 1f else -1f
        for (k in 0..1) {
            val cx = if (forward) w * (0.12f + k * 0.34f) else w * (0.88f - k * 0.34f)
            val p = Path().apply {
                moveTo(cx, h * 0.24f)
                lineTo(cx + dir * w * 0.30f, h * 0.50f)
                lineTo(cx, h * 0.76f)
                close()
            }
            drawPath(p, tint)
        }
        val bx = if (forward) w * 0.88f else w * 0.12f
        drawLine(tint, Offset(bx, h * 0.24f), Offset(bx, h * 0.76f),
            (w * 0.09f).coerceAtLeast(1.6.dp.toPx()), StrokeCap.Round)
    }
}

/** R142 · 代码绘制的返回箭头，给「输入朗读」「调音台弹层」这类独立浮层用。 */
@Composable
internal fun IconBack(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.62f, h * 0.20f)
            lineTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.80f)
        }
        drawPath(p, color = tint, style = Stroke(width = 2.4.dp.toPx(),
            cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun IconClose(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.10f).coerceAtLeast(1.8.dp.toPx())
        val p = w * 0.28f
        drawLine(tint, Offset(p, p), Offset(w - p, h - p), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - p, p), Offset(p, h - p), sw, StrokeCap.Round)
    }
}

@Composable
internal fun IconChevronDown(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.26f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.62f)
            lineTo(w * 0.74f, h * 0.38f)
        }
        drawPath(p, tint, style = Stroke(
            width = (w * 0.09f).coerceAtLeast(1.8.dp.toPx()),
            cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 播放列表 / 目录：三条横线，末条短一截。 */
@Composable
internal fun IconQueue(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = (h * 0.10f).coerceAtLeast(1.6.dp.toPx())
        listOf(0.28f, 0.50f, 0.72f).forEachIndexed { i, y ->
            val end = if (i == 2) w * 0.62f else w * 0.80f
            drawLine(tint, Offset(w * 0.20f, h * y), Offset(end, h * y),
                sw, StrokeCap.Round)
        }
    }
}

/** R159 · 续跑弹窗：圆形播放标记。纯 Canvas，随主题取色，不用字符「▶」。 */
@Composable
internal fun IconResume(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(tint, radius = w * 0.42f, center = Offset(w / 2f, h / 2f),
            style = Stroke(width = w * 0.06f))
        val tri = Path().apply {
            moveTo(w * 0.43f, h * 0.32f)
            lineTo(w * 0.73f, h * 0.50f)
            lineTo(w * 0.43f, h * 0.68f)
            close()
        }
        drawPath(tri, tint)
    }
}
