package com.tingben.app

import androidx.compose.ui.graphics.Color

/**
 * R396 · UI 单一真值源（R395 综合回看「四件套」的地基）。
 *
 * 这个文件的职责只有一件：把「画死的文案 / 计数 / 状态 / 颜色角色」收成
 * 可派生的类型，让同一处底层缺陷不再在 11+ 个页面反复显影。
 *
 * 硬边界：**不含任何业务判断、不调用任何业务方法、不读写任何存储**。
 * 分章规则、段号、勾章顺着做、断点续跑、拼章转码链、各开关默认值、
 * 请求文本留存、跨网中继 / 语音网关链路、日志 24h，一条都不在这里。
 * 本文件只提供「给定 state 该怎么显示」，不提供「state 从哪来」。
 */

// ── ① 状态机：所有计数 / 状态文案 / 可用性一律由 state 派生 ──────────────
/**
 * 列表页唯一状态。传入真实条目数与真实勾选集合大小，
 * 画面上的「N 条」「已选 N」「空态可不可用」全部由它算出来。
 * 禁止再在 Composable 里写 "已选 3" / "共 10 条" / "播放队列 · 4 条" 这类字面量。
 */
internal data class UiListState(
    val itemCount: Int,
    val selectedCount: Int = 0,
    val isSelecting: Boolean = false,
    val expandedId: String? = null
) {
    val isEmpty: Boolean get() = itemCount == 0
    val countLabel: String get() = "$itemCount 条"
    /** 画面上带「共」前缀的那一版（ArchiveUi 现用文案，输出一字不变）。 */
    val fullCountLabel: String get() = "共 $itemCount 条"
    val selectedLabel: String get() = "已选 $selectedCount"
    val fullSelectedLabel: String get() = "已选 $selectedCount 条"
    /** 空态下批量动作（一键复制 / 一键清空 / 反选）必须置灰。 */
    val bulkEnabled: Boolean get() = !isEmpty
    val selectEnabled: Boolean get() = !isEmpty && isSelecting
}

/** 开关页唯一状态：状态卡文案必须等于开关真值，禁止写死「默认关 / 已开启」。 */
internal data class UiSwitchState(val enabled: Boolean) {
    val statusLabel: String get() = if (enabled) "已开启" else "默认关"
    val hintLabel: String get() =
        if (enabled) "开＝手机主动轮询队列" else "默认关 · 手动开启"
}

/** 互斥动作按当前状态只留一个（置顶 / 取消置顶、启用 / 当前模式）。 */
internal fun exclusiveAction(pinned: Boolean): String =
    if (pinned) "取消置顶" else "置顶"

// ── ② 语义色角色：浅深只是同一角色的两套取值 ─────────────────────────────
internal enum class UiRole { PRIMARY, SECONDARY, TERTIARY, DANGER }

internal data class UiActionColors(
    val container: Color,
    val content: Color,
    val outline: Color?,
    /** DANGER 在非确认弹窗场景恒为 false：常态批量动作不准用警示色抢主视觉。 */
    val allowed: Boolean = true
)

/**
 * 按角色取色，不按「这一页看起来该用什么颜色」临时挑。
 * DANGER 只在确认弹窗的最终按钮上 allowed = true；调用方必须自己传
 * inConfirmDialog，这里不抛异常（避免 UI 层把运行时异常带进业务路径）。
 */
internal fun actionColors(
    role: UiRole,
    p: Pal,
    inConfirmDialog: Boolean = false
): UiActionColors = when (role) {
    UiRole.PRIMARY -> UiActionColors(p.primary, p.onPrimary, null)
    UiRole.SECONDARY -> UiActionColors(p.btnSecondary, p.onBtnSecondary, null)
    UiRole.TERTIARY -> UiActionColors(
        p.btnTertiary, p.onBtnTertiary, p.btnTertiaryLine
    )
    UiRole.DANGER -> UiActionColors(
        p.warn, p.onWarn, null, allowed = inConfirmDialog
    )
}

/** 守门用的单一判据：常态场景永远不允许 DANGER。 */
internal fun dangerAllowed(inConfirmDialog: Boolean): Boolean = inConfirmDialog

// ── ③ 模态层级协议：content → scrim → dialog ────────────────────────────
internal object UiLayer {
    const val CONTENT_Z = 0f
    const val SCRIM_Z = 1f
    const val DIALOG_Z = 2f
    /** 遮罩浓度（Android 15/16 edge-to-edge 下也要压住底部小白条区域）。 */
    const val SCRIM_ALPHA = 0.62f
    /** 弹窗打开时，全局悬浮进度球 / 底部操作条降到的透明度。 */
    const val BACKGROUND_DIM_WHEN_MODAL = 0.20f
    /** 弹窗打开时，底层控件是否仍参与命中测试：一律否。 */
    const val HIT_TEST_WHEN_MODAL = false
    /** 设计说明只能写在图外，禁止作为 UI 文案画进界面。 */
    const val DESIGN_NOTE_IN_UI = false
}

// ── ④ 设备层契约常量（与 UiInsets.kt 的扩展配套） ────────────────────────
internal object UiInsetContract {
    /** Apple HIG / WCAG 稳定硬指标：最小触控目标。 */
    const val MIN_TOUCH_DP = 44
    /** 系统手势小白条高度（金标联盟 ITGSA 适配公告所指区域）。 */
    const val GESTURE_BAR_DP = 24
    /** WCAG 2.2 AA 正文对比下限。 */
    const val CONTRAST_MIN = 4.5
    /** 系统字号放大到 200% 时布局不破版。 */
    const val FONT_SCALE_MAX = 2.0f
    /** 四向 inset 必须逐方向写清，不能只写「适配刘海屏」。 */
    val INSET_SIDES = listOf("top", "right", "bottom", "left")
}
