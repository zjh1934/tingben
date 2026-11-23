package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.tingben.app.diag.EventLog
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * R224 · 多选动作按钮：有底色、圆角、可按压，不再是一排平铺的字。
 * 之前底部条里全是 TextButton，看着像「弹出一排字」，用户分不出哪些能点。
 */
@Composable
internal fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    primary: Boolean = false,
    icon: UiIcon? = null
) {
    // R291 · 主按钮用实心主色 + onPrimary 文字，和次要的浅底 chip 一眼分得开
    val tint = when {
        primary -> palette.onPrimary
        danger -> palette.warn
        else -> palette.primary
    }
    val bg = when {
        primary -> palette.primary
        danger -> palette.warn.copy(alpha = 0.13f)
        else -> palette.primarySoft
    }
    // R363 起文字单行省略，字体放大时也不会画到色块外面；
    // R365 · 底部条不再用它，此组件只供分段看台等仍带文字的地方。
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(HiChipRadius))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        // R247 · 图标 + 文字并存：图标给一眼可辨的视觉锚，文字保住抽象动作的可读性。
        if (icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UiIconGlyph(icon, tint, glyphSize = 18.dp)
                Spacer(Modifier.width(5.dp))
                Text(label, fontSize = HiSectionSp, fontWeight = FontWeight.Medium,
                    color = tint, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(label, fontSize = HiSectionSp, fontWeight = FontWeight.Medium,
                color = tint, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * R365 · 底部条专用：一枚纯图标按钮，不渲染文字。
 *
 * 目录页本来就挤，带字的三行条把章节列表压掉一大截；新口径是「一排图标就够」。
 * 文字全撤（含「已选 N」「全选章节」「取消」），可读性靠三件事补齐：
 * 图标语义（contentDescription）、主色 / 危险色 / 禁用置灰三态、固定等宽点击区。
 * 动作本身一条不删；ActionChip 仍供分段看台等有文字的地方用。
 */
@Composable
internal fun IconChip(
    label: String,
    icon: UiIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false
) {
    val tint = when {
        !enabled -> palette.sub.copy(alpha = 0.45f)
        primary -> palette.onPrimary
        danger -> palette.warn
        else -> palette.primary
    }
    val bg = when {
        !enabled -> palette.primarySoft.copy(alpha = 0.45f)
        primary -> palette.primary
        danger -> palette.warn.copy(alpha = 0.13f)
        else -> palette.primarySoft
    }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(HiChipRadius))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        UiIconGlyph(icon, tint, glyphSize = 20.dp)
    }
}

/**
 * R134 · 统一多选态。
 *
 * 用户要的不是「长按弹一个菜单」，而是：长按 → 进入多选态 → 每行出现小方块可勾 →
 * 选完从底部弹出的操作条里批量操作。这里把「方块」和「底部条」做成全 App 唯一一套，
 * 章节列 / 可播列 / 分段看台共用，避免三处各画一套长得不一样的勾选框。
 */

/** 代码绘制的勾选方块：未选是空心圆角框，选中是实心 + 白色勾。 */
@Composable
internal fun SelectBox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = palette.primary,
    boxColor: Color = palette.card
) {
    Canvas(modifier) {
        val s = size.width.coerceAtMost(size.height)
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        val inset = s * 0.10f
        val sq = s - inset * 2f
        val radius = CornerRadius(s * 0.20f, s * 0.20f)
        if (checked) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(left + inset, top + inset),
                size = Size(sq, sq),
                cornerRadius = radius
            )
            val p = Path().apply {
                moveTo(left + s * 0.30f, top + s * 0.51f)
                lineTo(left + s * 0.44f, top + s * 0.65f)
                lineTo(left + s * 0.71f, top + s * 0.35f)
            }
            drawPath(p, boxColor, style = Stroke(
                width = (s * 0.09f).coerceAtLeast(1.6.dp.toPx()),
                cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawRoundRect(
                color = palette.sub.copy(alpha = 0.65f),
                topLeft = Offset(left + inset, top + inset),
                size = Size(sq, sq),
                cornerRadius = radius,
                style = Stroke(width = (s * 0.07f).coerceAtLeast(1.4.dp.toPx()))
            )
        }
    }
}

/**
 * 底部操作条：R365 · 单行纯图标，一行等宽铺开，不再占两三排。
 *
 * R363 的「顶行工具 + 主按钮独占整行 + 次要动作等宽一行」和更早 R291 的
 * 「主按钮固定 + 次要动作横滑」都跟本轮打架，按「只留最新」整段作废，
 * 不并存、不回潮。页面本来就挤，条子只留一行 44dp。
 * 动作由调用方给，组件不知道也不该知道「删的是章还是段」；
 * 没勾选时主按钮置灰、无可操作项收起，条子始终只有一行。
 */
@Composable
internal fun MultiSelectBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String = "全选",
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryIcon: UiIcon? = null,
    // R365 · 没勾选时主按钮不被摘掉，只置灰，避免条子在 4 枚 / 5 枚之间跳。
    primaryEnabled: Boolean = true,
    // R304 · 计数不画在页面上，只进 contentDescription，纯图标形态不破。
    showCount: Boolean = true,
    actions: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = palette.card),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allDesc = if (showCount) "$allLabel · 已选 $count" else allLabel
            IconChip(allDesc, UiIcon.ALL, onClick = {
                onSelectAll()
                EventLog.ui("MULTI_ALL_BAR", allLabel)
            }, modifier = Modifier.weight(1f))
            // R171 · 取消能力不撤，只从文字换成 × 图标，事件名不动。
            IconChip("取消多选", UiIcon.CANCEL, onClick = {
                onCancel()
                EventLog.ui("MULTI_CANCEL_BAR", "")
            }, modifier = Modifier.weight(1f))
            if (primaryLabel != null && onPrimary != null) {
                IconChip(primaryLabel, primaryIcon ?: UiIcon.SYNTH,
                    onClick = onPrimary, enabled = primaryEnabled,
                    primary = true, modifier = Modifier.weight(1f))
            }
            actions()
        }
    }
}
