package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * R247 · 代码自绘的小图标库：给 chip 与弹层按钮配 15dp 图标，不引图标库、
 * 不进 APK 任何资源文件，深浅主题随调用方传的 tint。
 *
 * 取舍：用户说「很多按钮不一定非就是字，它可以有图标啊」，但「拼成整章 /
 * 转 M4A / 另存 WAV」这类抽象动作纯图标没人认得出，所以一律**图标 + 文字**
 * 并存；文字保留也让 R224 以来按字面量定位的守门判据一条不用迁。
 * 全部相对坐标 0..1，只用 drawLine / drawPath / drawCircle 三种最稳的重载
 * （R246 真编译教训：drawLine 没有传 Stroke 对象的重载，只能具名 strokeWidth）。
 */
enum class UiIcon {
    MIXER, SETTINGS, SYNTH, MERGE, WAV, M4A, DELETE,
    PLAY, STOP, LISTEN, EXPORT, CANCEL, ALL, AUDIO, TASKS,
    // R320 · 设置页专用：原来每行用 title.take(1) 放大一个汉字冒充图标，
    // 既没有语义也撑不起视觉层级。全部换成相对坐标自绘的矢量图形。
    PROGRESS, RESUME, BELL, PALETTE, SHIELD, FOLDER, BROOM, BOOK
}

@Composable
internal fun UiIconGlyph(which: UiIcon, tint: Color, glyphSize: Dp = 15.dp) {
    Canvas(Modifier.size(glyphSize)) {
        // Canvas lambda 的 receiver 也有 size；参数改名 glyphSize 避免遮蔽。
        val w = this.size.width
        val h = this.size.height
        val sw = 1.6.dp.toPx()
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2),
                strokeWidth = sw, cap = StrokeCap.Round)
        fun ring(cx: Float, cy: Float, r: Float) =
            drawCircle(tint, radius = w * r, center = Offset(w * cx, h * cy),
                style = Stroke(width = sw))
        when (which) {
            UiIcon.MIXER -> {
                // 三根推子 + 错开的滑块点
                for (i in 0..2) {
                    val x = 0.20f + 0.30f * i.toFloat()
                    ln(x, 0.08f, x, 0.92f)
                }
                ring(0.20f, 0.32f, 0.085f)
                ring(0.50f, 0.62f, 0.085f)
                ring(0.80f, 0.46f, 0.085f)
            }
            UiIcon.SETTINGS -> {
                // 简化齿轮：内圆 + 八根短齿
                ring(0.50f, 0.50f, 0.24f)
                for (i in 0 until 8) {
                    val a = 2.0 * kotlin.math.PI * i.toDouble() / 8.0
                    val dx = kotlin.math.cos(a).toFloat()
                    val dy = kotlin.math.sin(a).toFloat()
                    ln(0.50f + dx * 0.34f, 0.50f + dy * 0.34f,
                       0.50f + dx * 0.46f, 0.50f + dy * 0.46f)
                }
            }
            UiIcon.SYNTH -> {
                // 四根高低不同的声波柱
                ln(0.16f, 0.42f, 0.16f, 0.58f)
                ln(0.38f, 0.24f, 0.38f, 0.76f)
                ln(0.61f, 0.14f, 0.61f, 0.86f)
                ln(0.84f, 0.36f, 0.84f, 0.64f)
            }
            UiIcon.MERGE -> {
                // 两段小方块由中间箭头连起来
                ln(0.08f, 0.30f, 0.36f, 0.30f)
                ln(0.08f, 0.30f, 0.08f, 0.70f)
                ln(0.08f, 0.70f, 0.36f, 0.70f)
                ln(0.64f, 0.30f, 0.92f, 0.30f)
                ln(0.92f, 0.30f, 0.92f, 0.70f)
                ln(0.64f, 0.70f, 0.92f, 0.70f)
                ln(0.38f, 0.50f, 0.66f, 0.50f)
                ln(0.58f, 0.39f, 0.66f, 0.50f)
                ln(0.58f, 0.61f, 0.66f, 0.50f)
            }
            UiIcon.WAV -> {
                // 波形折线
                val p = Path().apply {
                    moveTo(w * 0.06f, h * 0.50f)
                    lineTo(w * 0.22f, h * 0.18f)
                    lineTo(w * 0.38f, h * 0.82f)
                    lineTo(w * 0.56f, h * 0.26f)
                    lineTo(w * 0.74f, h * 0.74f)
                    lineTo(w * 0.94f, h * 0.50f)
                }
                drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
            }
            UiIcon.M4A -> {
                // 文件折角 + 音符点
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.10f)
                    lineTo(w * 0.62f, h * 0.10f)
                    lineTo(w * 0.86f, h * 0.34f)
                    lineTo(w * 0.86f, h * 0.90f)
                    lineTo(w * 0.20f, h * 0.90f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw))
                ring(0.50f, 0.62f, 0.09f)
                ln(0.59f, 0.62f, 0.59f, 0.34f)
            }
            UiIcon.DELETE -> {
                // 垃圾桶：盖把手 + 桶身 + 两条竖纹
                ln(0.14f, 0.24f, 0.86f, 0.24f)
                ln(0.38f, 0.14f, 0.62f, 0.14f)
                ln(0.22f, 0.24f, 0.28f, 0.88f)
                ln(0.78f, 0.24f, 0.72f, 0.88f)
                ln(0.28f, 0.88f, 0.72f, 0.88f)
                ln(0.42f, 0.40f, 0.42f, 0.72f)
                ln(0.58f, 0.40f, 0.58f, 0.72f)
            }
            UiIcon.PLAY -> {
                val p = Path().apply {
                    moveTo(w * 0.28f, h * 0.14f)
                    lineTo(w * 0.86f, h * 0.50f)
                    lineTo(w * 0.28f, h * 0.86f)
                    close()
                }
                drawPath(p, color = tint)
            }
            UiIcon.STOP -> {
                val p = Path().apply {
                    moveTo(w * 0.26f, h * 0.26f)
                    lineTo(w * 0.74f, h * 0.26f)
                    lineTo(w * 0.74f, h * 0.74f)
                    lineTo(w * 0.26f, h * 0.74f)
                    close()
                }
                drawPath(p, color = tint)
            }
            UiIcon.LISTEN -> {
                // 耳机：头梁 + 两个耳罩
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.56f)
                    lineTo(w * 0.18f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.22f)
                    lineTo(w * 0.82f, h * 0.46f)
                    lineTo(w * 0.82f, h * 0.56f)
                }
                drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                ln(0.18f, 0.56f, 0.18f, 0.82f)
                ln(0.82f, 0.56f, 0.82f, 0.82f)
            }
            UiIcon.EXPORT -> {
                // 框 + 向右上箭头
                ln(0.14f, 0.36f, 0.14f, 0.86f)
                ln(0.14f, 0.86f, 0.64f, 0.86f)
                ln(0.44f, 0.12f, 0.88f, 0.56f)
                ln(0.66f, 0.12f, 0.88f, 0.12f)
                ln(0.88f, 0.12f, 0.88f, 0.34f)
            }
            UiIcon.CANCEL -> {
                ln(0.24f, 0.24f, 0.76f, 0.76f)
                ln(0.76f, 0.24f, 0.24f, 0.76f)
            }
            UiIcon.ALL -> {
                // 方框 + 勾
                ln(0.16f, 0.16f, 0.84f, 0.16f)
                ln(0.84f, 0.16f, 0.84f, 0.84f)
                ln(0.84f, 0.84f, 0.16f, 0.84f)
                ln(0.16f, 0.84f, 0.16f, 0.16f)
                ln(0.30f, 0.52f, 0.44f, 0.68f)
                ln(0.44f, 0.68f, 0.72f, 0.34f)
            }
            UiIcon.AUDIO -> {
                // 喇叭 + 两道声波
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.40f)
                    lineTo(w * 0.34f, h * 0.40f)
                    lineTo(w * 0.56f, h * 0.20f)
                    lineTo(w * 0.56f, h * 0.80f)
                    lineTo(w * 0.34f, h * 0.60f)
                    lineTo(w * 0.12f, h * 0.60f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw))
                ln(0.68f, 0.34f, 0.82f, 0.22f)
                ln(0.68f, 0.66f, 0.82f, 0.78f)
            }
            UiIcon.TASKS -> {
                // 清单三条 + 圆点
                for (i in 0..2) {
                    val y = 0.24f + 0.26f * i.toFloat()
                    ring(0.18f, y, 0.05f)
                    ln(0.36f, y, 0.86f, y)
                }
            }
            // R320 · 以下八个全部只用 ln / ring / drawPath 三种最稳重载，
            // 不碰 drawArc 之类在 Compose 不同版本签名会漂移的 API。
            UiIcon.PROGRESS -> {
                // 底环 + 从 12 点起的实心进度弧 + 中心圆点
                ring(0.50f, 0.50f, 0.36f)
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.14f)
                    lineTo(w * 0.50f, h * 0.50f)
                    lineTo(w * 0.86f, h * 0.50f)
                }
                drawPath(p, color = tint, style = Stroke(width = sw * 1.4f, cap = StrokeCap.Round))
                drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.50f, h * 0.50f))
            }
            UiIcon.RESUME -> {
                // 两枚右向三角（继续 / 接着跑）
                for (i in 0..1) {
                    val x = 0.10f + 0.42f * i
                    val p = Path().apply {
                        moveTo(w * x, h * 0.20f)
                        lineTo(w * (x + 0.34f), h * 0.50f)
                        lineTo(w * x, h * 0.80f)
                        close()
                    }
                    drawPath(p, color = tint)
                }
            }
            UiIcon.BELL -> {
                // 铃铛：罩体 + 顶钮 + 铃舌
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.68f)
                    lineTo(w * 0.28f, h * 0.42f)
                    lineTo(w * 0.42f, h * 0.30f)
                    lineTo(w * 0.58f, h * 0.30f)
                    lineTo(w * 0.72f, h * 0.42f)
                    lineTo(w * 0.78f, h * 0.68f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                ln(0.22f, 0.68f, 0.78f, 0.68f)
                ring(0.50f, 0.22f, 0.06f)
                ring(0.50f, 0.80f, 0.07f)
            }
            UiIcon.PALETTE -> {
                // 调色盘：外轮廓 + 拇指孔 + 三个颜料点
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.84f, h * 0.24f)
                    lineTo(w * 0.88f, h * 0.60f)
                    lineTo(w * 0.62f, h * 0.88f)
                    lineTo(w * 0.34f, h * 0.86f)
                    lineTo(w * 0.14f, h * 0.58f)
                    lineTo(w * 0.16f, h * 0.28f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw))
                ring(0.36f, 0.62f, 0.09f)
                ring(0.60f, 0.34f, 0.06f)
                ring(0.72f, 0.48f, 0.06f)
                ring(0.52f, 0.70f, 0.06f)
            }
            UiIcon.SHIELD -> {
                // 盾形 + 中间勾
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.10f)
                    lineTo(w * 0.84f, h * 0.24f)
                    lineTo(w * 0.80f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.90f)
                    lineTo(w * 0.20f, h * 0.62f)
                    lineTo(w * 0.16f, h * 0.24f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                ln(0.32f, 0.50f, 0.45f, 0.64f)
                ln(0.45f, 0.64f, 0.70f, 0.36f)
            }
            UiIcon.FOLDER -> {
                // 文件夹：带折角的底盒 + 盖片
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.28f)
                    lineTo(w * 0.40f, h * 0.28f)
                    lineTo(w * 0.48f, h * 0.38f)
                    lineTo(w * 0.88f, h * 0.38f)
                    lineTo(w * 0.88f, h * 0.84f)
                    lineTo(w * 0.12f, h * 0.84f)
                    close()
                }
                drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                ln(0.12f, 0.28f, 0.12f, 0.20f)
                ln(0.12f, 0.20f, 0.36f, 0.20f)
            }
            UiIcon.BROOM -> {
                // 扫帚：斜杆 + 三根刷毛 + 捆扎线
                ln(0.72f, 0.14f, 0.38f, 0.60f)
                ln(0.30f, 0.56f, 0.20f, 0.86f)
                ln(0.42f, 0.62f, 0.38f, 0.90f)
                ln(0.54f, 0.60f, 0.58f, 0.88f)
                ln(0.26f, 0.66f, 0.62f, 0.66f)
            }
            UiIcon.BOOK -> {
                // 摊开的书：书脊 + 左右页边 + 两道文字线
                ln(0.50f, 0.18f, 0.50f, 0.86f)
                ln(0.50f, 0.18f, 0.14f, 0.24f)
                ln(0.14f, 0.24f, 0.14f, 0.80f)
                ln(0.14f, 0.80f, 0.50f, 0.86f)
                ln(0.50f, 0.18f, 0.86f, 0.24f)
                ln(0.86f, 0.24f, 0.86f, 0.80f)
                ln(0.86f, 0.80f, 0.50f, 0.86f)
                ln(0.22f, 0.40f, 0.40f, 0.38f)
                ln(0.60f, 0.38f, 0.78f, 0.40f)
            }
        }
    }
}
