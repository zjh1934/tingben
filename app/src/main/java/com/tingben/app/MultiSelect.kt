package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R224 · 多选动作按钮：有底色、圆角、可按压，不再是一排平铺的字。
 * 之前底部条里全是 TextButton，看着像「弹出一排字」，用户分不出哪些能点。
 */
@Composable
internal fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val tint = if (danger) palette.warn else palette.primary
    Box(
        modifier.padding(end = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) palette.warn.copy(alpha = 0.13f)
                        else palette.primarySoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = tint, maxLines = 1)
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
 * 底部胶囊操作条：全选 / 已选计数 / 一排可横滑的动作 / 取消。
 * 动作由调用方给，组件不知道也不该知道「删的是章还是段」。
 */
@Composable
internal fun MultiSelectBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String = "全选",
    actions: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = palette.card),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $count", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = palette.primary, modifier = Modifier.padding(start = 8.dp))
            ActionChip(allLabel, onClick = {
                onSelectAll()
                EventLog.ui("MULTI_ALL_BAR", allLabel)
            })
            // 动作可能多，横滑兜底，不挤压计数与取消
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
            // R171 · 原来只有一个灰色 ×，用户「不知道这个勾选态怎么取消」。
            // 换成看得见的「取消」文字，图标留作辅助，事件名不动。
            ActionChip("取消", onClick = {
                onCancel()
                EventLog.ui("MULTI_CANCEL_BAR", "")
            }, modifier = Modifier.padding(start = 2.dp))
        }
    }
}
