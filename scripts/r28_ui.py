#!/usr/bin/env python3
# R28 · 看台 UI：一眼判定条 + 逐段内容 + 段级重做（只标脏，不清整章）
import pathlib, shutil
P = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/com/tingben/app/MainActivity.kt"
shutil.copy(P, pathlib.Path(str(P) + ".r28bak"))
s = P.read_text()

def sub(old, new, tag):
    global s
    n = s.count(old)
    assert n == 1, f"[{tag}] 命中 {n} 次：{old[:70]!r}"
    s = s.replace(old, new, 1)

sub("""            val seenTask = remember(tick) { TaskResume.load(c) }
            val segs = remember(tick, seenTask) { Workbench.segments(c, seenTask) }
            val rest = remember(tick, seenTask) { Workbench.remainingCount(c, seenTask) }
""",
"""            val seenTask = remember(tick) { TaskResume.load(c) }
            // R28 · 点「重做」后靠这个 nonce 重扫磁盘，不靠猜状态。
            var redoNonce by remember { mutableStateOf(0) }
            val segs = remember(tick, seenTask, redoNonce) { Workbench.segments(c, seenTask) }
            val rest = remember(tick, seenTask, redoNonce) { Workbench.remainingCount(c, seenTask) }
            val verdict = remember(tick, seenTask, redoNonce) { Workbench.verdict(c, seenTask) }
""", "nonce")

sub("""            CardBox {
                Row(Modifier.clickable { segOpen = !segOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold,
""",
"""            // R28 · 「语言模型到底有没有起作用」不用贴日志给我，App 里一眼判。
            // 四种口径分开：直接朗读 / 还没数据 / 模型生效 / 全是直读。
            CardBox {
                Text("语言模型是否生效", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                val vText = when {
                    seenTask?.direct == true -> "本次是「直接朗读」，没有使用语言模型"
                    verdict == null -> "还没落盘段映射；跑一次「标注后合成」后这里给出结论"
                    else -> verdict.label
                }
                Text(vText, fontSize = 12.sp, color = when {
                    seenTask?.direct == true -> Sub
                    verdict == null -> Sub
                    verdict.effective -> Good
                    else -> Warn
                })
                if (verdict != null && verdict.unknown > 0)
                    Text("其中 ${verdict.unknown} 段是旧缓存（升级前跑的），没记来源，不冒充已标注。",
                        fontSize = 11.sp, color = Sub)
            }
            Spacer(Modifier.height(8.dp))
            CardBox {
                Row(Modifier.clickable { segOpen = !segOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold,
""", "verdict_card")

sub("""                Text("绿=已有声音 · 蓝=模型真标注 · 黄=模型没采纳按原文直读 · "
                        + "灰=没轮到/旧缓存未记来源；点一小格看原文、试听这一段。",
""",
"""                Text("绿=已有声音 · 蓝=模型真标注 · 黄=模型没采纳按原文直读 · "
                        + "灰=没轮到/旧缓存未记来源；点一小格看原文、试听、重做这一段。",
""", "legend")

OLD_DETAIL = """                    segSel?.let { sel ->
                        val s = segs.find { it.index == sel }
                        if (s != null) {
                            Spacer(Modifier.height(10.dp))
                            Text("第 ${s.index + 1} 段 · " +
                                    if (s.hasAudio) "已合成 ${fmtTime(s.durationMs)}" +
                                            when (s.src) {
                                                "MODEL" -> "（模型标注）"
                                                "FILL" -> "（模型未采纳，按原文直读）"
                                                else -> ""
                                            }
                                    else when (s.src) {
                                        "MODEL" -> "模型已标注，还没合成"
                                        "FILL" -> "模型产出没被采纳，按原文直读"
                                        "UNKNOWN" -> "已落盘（旧缓存没记来源）"
                                        else -> "还没轮到"
                                    },
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary)
                            Spacer(Modifier.height(5.dp))
                            Text(if (s.preview.isBlank()) "（暂无文字预览）" else s.preview + "…",
                                fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                if (s.wav != null) {
                                    val label = "第 ${s.index + 1} 段"
                                    val onThis = pqSeg.label == label
                                    Button(onClick = {
                                        PlayQueue.setTracks(listOf(Track(s.wav, label)))
                                        PlayQueue.playFrom(0)
                                    }) {
                                        Text(if (onThis && pqSeg.isPlaying) "重播这一段" else "试听这一段",
                                            fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { PlayQueue.release() }) {
                                        Text(if (onThis && pqSeg.isPlaying) "停止" else "关掉预览",
                                            fontSize = 11.5.sp, color = Sub)
                                    }
                                } else {
                                    Text("这一段还没生成声音", fontSize = 11.5.sp, color = Sub)
                                }
                            }
                        }
                    }
"""

NEW_DETAIL = """                    segSel?.let { sel ->
                        val s = segs.find { it.index == sel }
                        if (s != null) {
                            // R28 · 这里显示的是「最终进 TTS 的那一条」，不是模型原始产出。
                            // 逐段标来源，不按「前 N 条」猜（R27 的缺口补齐会插在中间）。
                            val srcText = when (s.fromModel) {
                                true -> "模型真标注"
                                false -> "模型产出没被采纳，按原文直读"
                                null -> "旧缓存，没记来源"
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("第 ${s.index + 1} 段 · 第 ${s.block + 1} 块第 ${s.ann + 1} 条 · " +
                                    srcText + if (s.hasAudio) " · 已合成" else " · 还没声音",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (s.fromModel == true) Good else if (s.fromModel == false) Warn else Sub)
                            if (s.pendingRedo)
                                Text("已标记待重做，去任务页点「继续制作」", fontSize = 11.sp, color = Warn)
                            Spacer(Modifier.height(5.dp))
                            Text(if (s.text.isBlank()) "（这一格没有落盘文字，可能是旧缓存）" else s.text,
                                fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                            if (s.instruct.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("${s.speaker}｜${s.instruct}", fontSize = 11.sp, color = Primary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                if (s.wav != null) {
                                    val label = "第 ${s.index + 1} 段"
                                    val onThis = pqSeg.label == label
                                    Button(onClick = {
                                        PlayQueue.setTracks(listOf(Track(s.wav, label)))
                                        PlayQueue.playFrom(0)
                                    }) {
                                        Text(if (onThis && pqSeg.isPlaying) "重播这一段" else "试听这一段",
                                            fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { PlayQueue.release() }) {
                                        Text("关掉预览", fontSize = 11.5.sp, color = Sub)
                                    }
                                } else {
                                    Text("这一段还没生成声音", fontSize = 11.5.sp, color = Sub)
                                }
                            }
                            // R28 · 段级重做：语义等同 ReaderVoice Scheduler.invalidate(unitIds)，
                            // 只把目标单元标脏，禁整章 / 整本清缓存。
                            if (seenTask?.direct != true && s.wavName.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Row {
                                    TextButton(onClick = {
                                        val w = Workbench.workDir(c, seenTask)
                                        val ok = w?.let { SegPlan.redoSynthesize(it,
                                            SegMeta(s.index, s.block, s.ann, s.fromModel,
                                                s.text, s.speaker, s.instruct, s.wavName)) } ?: false
                                        Toast.makeText(c,
                                            if (ok) "已删这段声音，去任务页点「继续制作」重合成" else "没找到这段文件",
                                            Toast.LENGTH_SHORT).show()
                                        redoNonce++
                                    }) { Text("重合成这一段", fontSize = 11.5.sp, color = Primary) }
                                    Spacer(Modifier.width(6.dp))
                                    TextButton(onClick = {
                                        val w = Workbench.workDir(c, seenTask)
                                        val all = w?.let { SegPlan.readMap(it) } ?: emptyList()
                                        val killed = w?.let { SegPlan.redoBlock(it, s.block, all) } ?: 0
                                        Toast.makeText(c,
                                            "已清第 ${s.block + 1} 块标注和 $killed 段声音，点「继续制作」重跑语言模型",
                                            Toast.LENGTH_LONG).show()
                                        redoNonce++
                                    }) { Text("重新标注本块", fontSize = 11.5.sp, color = Warn) }
                                }
                            }
                        }
                    }
"""
sub(OLD_DETAIL, NEW_DETAIL, "detail")

P.write_text(s)
print("MainActivity.kt 已改")
