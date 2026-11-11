package com.tingben.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import java.io.File

// @ui-part: Shell
@Composable
internal fun KeepScreenOn() {
    val st by Producer.state.collectAsState()
    val act = LocalContext.current as? android.app.Activity
    DisposableEffect(st.running) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (st.running) act?.window?.addFlags(flag) else act?.window?.clearFlags(flag)
        onDispose { act?.window?.clearFlags(flag) }
    }
}

internal fun queryName(c: Context, uri: Uri): String {
    var name = ""
    c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && cur.moveToFirst()) name = cur.getString(i)
    }
    return name
}

// 统一返回表（R130）：哪一页返回到哪一页，只在这里定义一次。
// 根级页（书架 / 任务 / 设置）靠抽屉切换，没有返回键；
// 二级页一律回自己的来源，栈被清掉时也按这张表兜底，不会莫名跳回书架。
// R162 · 主页只有输入框这一屏。R230 · ＋ 与齿轮都从主页右上角撤掉：
// 导入挪到抽屉顶部独占一排、设置挪到抽屉左下角；任务/调音留在顶栏右侧。
internal val ROOT_SCREENS = setOf(Screen.LIBRARY)

internal fun canGoBack(s: Screen): Boolean = s !in ROOT_SCREENS

internal fun backTargetOf(s: Screen, modelsFrom: Screen): Screen =
    when (s) {
        // 任务页不再有常驻入口（从通知/书里进），回退统一到主页
        Screen.TASKS -> Screen.LIBRARY
        Screen.MODELS -> modelsFrom
        else -> Screen.LIBRARY
    }

internal fun chromeOf(s: Screen): ChromeSpec = when (s) {
    // R164 · 品牌名从顶栏撤掉。R230 · 右上角 ＋/齿轮 全部撤掉，
    // 首页只留 ☰ 与右侧「调音 / 任务」两枚（trailing 由 App 层挂）。
    Screen.LIBRARY  -> ChromeSpec("", TAB_LIBRARY)
    Screen.PRODUCE  -> ChromeSpec("制作设置", TAB_LIBRARY, canBack = true)
    // R218 · 目录页顶栏直接写书名，不写「目录」二字（都进这本书了）。
    // 标题由 App 层按 bookId 覆盖；这里留空串占位。
    Screen.CHAPTERS -> ChromeSpec("", TAB_LIBRARY, canBack = true)
    Screen.TASKS    -> ChromeSpec("任务", TAB_TASKS, canBack = true)
    Screen.SETTINGS -> ChromeSpec("设置", canBack = true)
    Screen.MODELS   -> ChromeSpec("模型", canBack = true)
}

@Composable
internal fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

