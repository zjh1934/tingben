# -*- coding: utf-8 -*-
"""R66 · 1.1.0 语言模型退场：用户可见层真删、制作入口恒走直接朗读。
幂等：每项替换若已生效则跳过；末尾打印改动计数。"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, 'app/src/main/java/com/tingben/app')
n = [0]

def rd(p): return open(p, encoding='utf-8').read()
def wr(p, t): open(p, 'w', encoding='utf-8').write(t)

def rep(p, old, new, must=True):
    t = rd(p)
    if old in t:
        c = t.count(old)
        t = t.replace(old, new)
        wr(p, t); n[0] += c
    elif must and new not in t:
        print('!! 未命中:', p.split('/')[-1], repr(old[:70])); sys.exit(1)

def cut(p, start_mark, end_mark, keep_end=True):
    """删掉 [start_mark, end_mark) 之间的文本（含 start_mark 所在行整行由调用者保证）。"""
    t = rd(p)
    a = t.find(start_mark)
    if a < 0: return
    b = t.find(end_mark, a + len(start_mark))
    if b < 0: print('!! 找不到结束标记', p, end_mark[:40]); sys.exit(1)
    t = t[:a] + (t[b:] if keep_end else t[b + len(end_mark):])
    wr(p, t); n[0] += 1

# ── 字符串字面量内的旧说法 → 中性词（不改任何标识符，编译期零风险） ──
MAP = [
    ('数据标注', '分段处理'), ('语言模型', '文本处理'), ('文本模型', '文本处理'),
    ('标注', '分段'), ('4B', '旧权重'), ('Qwen3-4B', '旧权重'),
    ('Huihui', '旧权重'), ('no_think', '旧开关'), ('A/B', '对照'),
    ('MODEL_ID', 'ENGINE_INFO'), ('m2507', 'v11'), ('prompt_zh', 'prompt'),
]
def neutralize(p):
    t = rd(p); out = []
    for line in t.split('\n'):
        if '"""' in line or '.mode ==' in line or '.mode !=' in line or '"mode" to' in line:
            out.append(line); continue
        def f(m):
            s = m.group(0); inner = s[1:-1]
            for a, b in MAP: inner = inner.replace(a, b)
            return '"' + inner + '"'
        out.append(re.sub(r'"(?:[^"\\\n]|\\.)*"', f, line))
    new = '\n'.join(out)
    if new != t: wr(p, new); n[0] += 1

# ═══ 1. AppChrome.kt ═══
p = os.path.join(J, 'AppChrome.kt')
rep(p, '/** P0 · 语言模型设置独立目的地。旧 7 项下标一个不改，只追加。 */\ninternal const val NAV_LLM = 7\n', '')
rep(p, '        NAV_LLM to "语言模型",\n', '')

# ═══ 2. MainActivity.kt ═══
p = os.path.join(J, 'MainActivity.kt')
rep(p, 'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST, MIXER, LLM, CHAPTERS }',
       'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST, MIXER, CHAPTERS }')
rep(p, '        Screen.LLM -> NAV_LLM\n', '')
rep(p, '                        NAV_LLM -> Screen.LLM\n', '')
rep(p, '    Screen.LLM      -> ChromeSpec("语言模型设置")\n', '')
rep(p, '                    onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER },\n'
       '                    onLlm = { drawerReturn = Screen.SETTINGS; screen = Screen.LLM })',
       '                    onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER })')
rep(p, '                Screen.LLM -> LlmSettingsScreen(ctx, refresh,\n'
       '                    onModels = { modelsFrom = Screen.LLM; screen = Screen.MODELS })\n', '')
rep(p, ', onLlm: () -> Unit) {', ') {')
rep(p, ' * P0 · 设置页只管软件：外观、数据与日志、后台与关于、一行语言模型入口。',
       ' * R66 · 设置页只管软件：外观、数据与日志、后台与关于；模型参数不再有独立页。')
rep(p, '            SettingInfoRow("原始产出", "现有", "按 run 分目录", "每次标注一块落一份全文")',
       '            SettingInfoRow("分段记录", "现有", "按 run 分目录", "每次制作落一份段映射")')
rep(p, '        var moreOpen by rememberSaveable { mutableStateOf(true) }\n', '')
cut(p, '        SettingsSection("更多", "独立页", moreOpen, { moreOpen = it }) {',
       '        Text("改动立即保存 · 对所有新任务生效 · 正在跑的任务不受影响",')
rep(p, 'Text(if (missing.isEmpty()) "3 个模型文件都准备好了"',
       'Text(if (missing.isEmpty()) "2 个声音模型都准备好了"')

# 诊断摘要：删模型/通过率/旧审计段，只留设备与引擎
cut(p, '        append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append(\'\\n\')',
       '        val crash = EventLog.lastCrash()')
t = rd(p)
t = t.replace('        val crash = EventLog.lastCrash()',
    '        append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append(\'\\n\')\n'
    '        append("引擎 ").append(if (NativeEngine.isAvailable()) "已装载" else "未装载").append(\'\\n\')\n'
    '        val crash = EventLog.lastCrash()', 1)
wr(p, t); n[0] += 1

# 删 LlmSettingsScreen 整页（从注释块到「⑤b 模型页」之前）
cut(p, '/**\n * P0 · 语言模型设置页：只管「标注后合成」那份 GGUF 与它的参数。',
       '// ── ⑤b 模型页 ────────────────────────────────────────────────')

# 模型页只列两份声音模型
rep(p, '            "语言模型" to ModelStore.LLM,\n', '')
t = rd(p)
t = re.sub(r'                            "语言模型" -> "[^"]*",\n', '', t)
wr(p, t); n[0] += 1

# 进度页：删「③ 语言模型是否生效」整卡
cut(p, '        // ③ 语言模型：整卡折成一行，结论仍读 Workbench.verdict，不删数据、不画假开关',
       '        // ④ 分段看台')
# 五步 → 四步
rep(p, '        val steps = listOf("拆章", "理解文字", "生成声音", "整理音频", "完成")\n'
       '        val curStep = when {\n'
       '            st.output != null -> 5\n'
       '            st.stage.contains("分章") -> 0\n'
       '            st.stage.contains("理解") -> 1\n'
       '            st.stage.contains("生成声音") -> 2\n'
       '            st.stage.contains("整理") -> 3\n'
       '            st.stage == "完成" -> 4\n'
       '            else -> -1\n'
       '        }',
       '        val steps = listOf("拆章", "生成声音", "整理音频", "完成")\n'
       '        val curStep = when {\n'
       '            st.output != null -> 4\n'
       '            st.stage.contains("分章") -> 0\n'
       '            st.stage.contains("生成声音") -> 1\n'
       '            st.stage.contains("整理") -> 2\n'
       '            st.stage == "完成" -> 3\n'
       '            else -> -1\n'
       '        }')
# 看台整段改写：点格改看送进声音模型的正文，不再读原始产出
t = rd(p)
a = t.find('        // ④ 分段看台：板块常驻不折叠，8 个一小块；点格子展开「模型吐出来的原貌」')
if a < 0:
    a = t.find('        // ④ 分段看台')
b = t.find('        Spacer(Modifier.height(18.dp))', a)
if a >= 0 and b > a:
    NEW = '''        // ④ 分段看台：板块常驻不折叠，8 个一小块；点格子展开送进声音模型的正文
        Spacer(Modifier.height(10.dp))
        CardBox {
            Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("绿=已出声 · 蓝=正在合成 · 灰=未开始 · 点格子看正文",
                fontSize = 10.sp, color = Sub)
            if (segs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("这次还没落盘分段记录；跑一次制作后这里才有点。", fontSize = 11.sp, color = Sub)
            } else {
                Spacer(Modifier.height(9.dp))
                segs.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { sg ->
                            val base = when {
                                sg.hasAudio -> Good
                                sg.wav != null -> Primary
                                else -> Line
                            }
                            Box(
                                Modifier.size(30.dp)
                                    .background(
                                        if (segSel == sg.index) PrimarySoft else base.copy(alpha = 0.18f),
                                        RoundedCornerShape(8.dp))
                                    .clickable { segSel = if (segSel == sg.index) null else sg.index },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${sg.index + 1}", fontSize = 10.sp,
                                    color = if (segSel == sg.index) Primary else base)
                                if (sg.hasAudio)
                                    Text("▶", fontSize = 6.sp, color = Good,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
                segSel?.let { sel ->
                    val sg = segs.find { it.index == sel }
                    if (sg != null) {
                        val label = "第 ${sg.index + 1} 段"
                        val onThis = pqSeg.label == label
                        val playing = onThis && pqSeg.isPlaying
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${label} · 第 ${sg.block + 1} 块 · 送进声音模型的正文",
                                fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (sg.wav != null) {
                                Box(Modifier.size(32.dp)
                                    .background(if (playing) Primary else PrimarySoft,
                                        RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (playing) PlayQueue.pause()
                                        else if (onThis) PlayQueue.resume()
                                        else {
                                            PlayQueue.setTracks(listOf(Track(sg.wav!!, label)))
                                            PlayQueue.playFrom(0)
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Text(if (playing) "⏸" else "▶", fontSize = 13.sp,
                                        color = if (playing) Card else Primary)
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(if (sg.text.isBlank()) "（这一段没有落盘文字）" else sg.text,
                            fontSize = 11.sp, color = Color(0xFF20242E), lineHeight = 17.sp)
                    }
                }
            }
        }
'''
    t = t[:a] + NEW + t[b:]
    wr(p, t); n[0] += 1
else:
    print('!! 看台区间未定位', a, b); sys.exit(1)

# 任务中心档位只留一种
rep(p, '    val modeText = when {\n        t == null -> ""\n        t.direct -> "直接朗读"\n'
       '        t.think -> "标注 · 思考档"\n        else -> "标注 · 快速档"\n    }',
       '    val modeText = if (t == null) "" else "直接朗读"')
# 制作准备页档位文案
rep(p, 'Text(if (m == ProduceMode.DIRECT) "直接朗读" else "标注后合成（实验性）",',
       'Text("直接朗读",')

# 目录页调用点补「调音」入口（点合成之前先进调音台）
rep(p, '                    onPlayBook = { miniBook = it })',
       '                    onPlayBook = { miniBook = it },\n'
       '                    onMixer = { drawerReturn = Screen.CHAPTERS; screen = Screen.MIXER })')

# 所有制作入口恒走直接朗读
t = rd(p)
t = t.replace('Producer.start(ctx, b, sel, t?.direct ?: false,', 'Producer.start(ctx, b, sel, true,')
t = t.replace('Producer.start(ctx, b, t.only, t.direct, t.think, t.config)',
              'Producer.start(ctx, b, t.only, true, t.think, t.config)')
t = t.replace('Producer.start(c, b, t.only, t.direct, t.think, t.config); onProgress() }',
              'Producer.start(c, b, t.only, true, t.think, t.config); onProgress() }')
wr(p, t); n[0] += 1

# ═══ 3. ChapterHub.kt ═══
p = os.path.join(J, 'ChapterHub.kt')
rep(p, 'private val HubTitles = listOf("原始", "标注", "合成", "可播")',
       'private val HubTitles = listOf("章节", "合成", "可播")')
rep(p, '    data class Annotated(val i: Int) : Pane()\n', '')
rep(p, 'private enum class HubAction { ANNOTATE, DIRECT, ANNOTATE_SYNTH }',
       'private enum class HubAction { SYNTH }')
rep(p, '            annotated = if (a != null && a.mode == "标注后合成") 1f\n'
       '                        else if (live != null && inScope(ch.index)) live else 0f,',
       '            annotated = if (a != null) 1f\n'
       '                        else if (live != null && inScope(ch.index)) live else 0f,')
rep(p, '            unannotatedAudio = a != null && a.mode != "标注后合成"',
       '            unannotatedAudio = false')
rep(p, 'internal fun ChapterHubScreen(\n    c: Context, id: String?, refresh: Int,\n'
       '    onBack: () -> Unit,\n    onProgress: () -> Unit,\n    onPlayBook: (String) -> Unit\n) {',
       'internal fun ChapterHubScreen(\n    c: Context, id: String?, refresh: Int,\n    onBack: () -> Unit,\n    onProgress: () -> Unit,\n    onPlayBook: (String) -> Unit,\n    onMixer: () -> Unit\n) {')
rep(p, '        val direct = a == HubAction.DIRECT\n'
       '        Producer.start(c, book, selected, directRead = direct, config = ProduceConfig())',
       '        Producer.start(c, book, selected, directRead = true, config = ProduceConfig())')
rep(p, '            "书=${book.title} 章数=$n 档=${if (direct) "直接朗读" else "标注后合成"}")',
       '            "书=${book.title} 章数=$n 档=直接朗读")')
rep(p, '            // 顶部四个文字标签：当前列下划线，不画圆点；滚动反向联动 current。',
       '            // 顶部三个文字标签：当前列下划线，不画圆点；滚动反向联动 current。')
rep(p, '                repeat(4) { col ->', '                repeat(3) { col ->')
rep(p, '                            1 -> AnnColumn(ui, { i -> pane = Pane.Annotated(i) })\n'
       '                            2 -> SynColumn(ui, { i -> playChapter(i, single = true) })',
       '                            1 -> SynColumn(ui, { i -> playChapter(i, single = true) })')
t = rd(p)
t = t.replace('                        if (r.unannotatedAudio) Text("未标注 · 可单章听", fontSize = 10.sp, color = HubWarn)\n'
              '                        else if (!done && p > 0f) Text("整本进度 ${(p * 100).toInt()}%", fontSize = 10.sp, color = HubSub)',
              '                        if (!done && p > 0f) Text("整本进度 ${(p * 100).toInt()}%", fontSize = 10.sp, color = HubSub)')
wr(p, t); n[0] += 1
# 底部小条：三条 → 一条 + 调音
t = rd(p)
a = t.find('                Column(Modifier.padding(12.dp)) {\n                    Text("已选 ${selected.size} 章"')
b = t.find('            }\n        }\n\n        // 覆盖层', a)
if a >= 0 and b > a:
    NEW = '''                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 章", fontSize = 12.sp, color = HubSub,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onMixer) { Text("调音 ›", fontSize = 12.sp) }
                        TextButton(onClick = { startAction(HubAction.SYNTH) }) {
                            Text("合成音频", fontSize = 12.sp)
                        }
                    }
                }
'''
    t = t[:a] + NEW + t[b:]
    wr(p, t); n[0] += 1
elif 'Text("调音 ›"' in t and 'HubAction.SYNTH' in t:
    pass  # 已改过，幂等
else:
    print('!! 底部小条区间未定位', a, b); sys.exit(1)
# 覆盖层删 Annotated 分支
cut(p, '                is Pane.Annotated -> AnnotatedPane(c, book, chapters, chunks, p.i,',
       '            }\n        }\n    }\n}')

# ═══ 3b. AudioLibrary.kt：第四列门槛从「标注后合成」改成「已合成」 ═══
p2 = os.path.join(J, 'core/AudioLibrary.kt')
rep(p2, '            mode = if (direct) "直接朗读" else "标注后合成",',
       '            mode = "直接朗读",')
rep(p2, '    /** 第四列准入门槛：标注后合成 + 索引已知。直接朗读的成品只能进第三列。 */\n'
       '    fun playableChapters(c: Context, bookId: String): Set<Int> =\n'
       '        list(c).filter { it.bookId == bookId && it.chaptersKnown && it.mode == "标注后合成" }\n'
       '            .flatMap { it.chapters }.toSet()',
       '    /** 第三列门槛：索引已知且成品存在；1.1.0 起不再区分制作档位。 */\n'
       '    fun playableChapters(c: Context, bookId: String): Set<Int> =\n'
       '        list(c).filter { it.bookId == bookId && it.chaptersKnown }\n'
       '            .flatMap { it.chapters }.toSet()')
rep(p2, '        latestByChapter(c, bookId).filter { (_, a) -> a.mode == "标注后合成" }\n'
       '            .toList().sortedBy { it.first }',
       '        latestByChapter(c, bookId).toList().sortedBy { it.first }')
rep(p2, '    /** 第三列单章播放：直接朗读成品也算，但未标注。 */',
       '    /** 第三列单章播放：有成品就能听。 */')

# ═══ 4. Pipeline.kt：模型缺失不再拦、start 恒直接朗读 ═══
p = os.path.join(J, 'core/Pipeline.kt')
rep(p, '        if (llm(c) == null) m.add("语言模型")\n', '')
rep(p, '        current = TaskInfo(book.id, book.title, onlyChapters, directRead, thinkOn,',
       '        current = TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,')
rep(p, '        AnnCache.cleanup(AnnCache.dirFor(c, book.id, directRead, thinkOn, onlyChapters))',
       '        AnnCache.cleanup(AnnCache.dirFor(c, book.id, true, thinkOn, onlyChapters))')
rep(p, '        run(c, book, onlyChapters, directRead, thinkOverride, config)',
       '        run(c, book, onlyChapters, true, thinkOverride, config)')
rep(p, '            (if (directRead) "直接朗读（不过语言模型）" else "标注后合成") +',
       '            "直接朗读" +')
rep(p, 'else "标注后合成"', 'else "分段后合成"')

# ═══ 5. 全局字符串中性化（含引擎内部旧日志，用户复制诊断也看不到旧说法） ═══
for dirpath, _, fns in os.walk(J):
    for f in fns:
        if f.endswith('.kt'): neutralize(os.path.join(dirpath, f))

# ═══ 6. 版本 / manifest / CI ═══
p = os.path.join(ROOT, 'app/build.gradle.kts')
rep(p, 'versionName = "1.0.0+"', 'versionName = "1.1.0+"')
import json
p = os.path.join(ROOT, 'contracts/model-manifest.json')
d = json.loads(rd(p))
d['runtime_policy'] = '只加载声音模型两份 GGUF；正文整段直接送声音模型，不再加载第二份权重。'
d['models'] = [m for m in d.get('models', []) if m.get('slot') != 'llm']
d['excluded'] = [e for e in d.get('excluded', []) if 'thinking' not in e.get('file', '')]
d.pop('baseline', None)
d['verified_by'] = '权重口径来自 Qwen 官方模型卡；1.1.0 起只保留声音模型两份 GGUF。'
wr(p, json.dumps(d, ensure_ascii=False, indent=2) + '\n'); n[0] += 1

p = os.path.join(ROOT, '.github/workflows/ci.yml')
t = rd(p)
t = t.replace('name: 听本 1.0.0+${{ github.run_number }}', 'name: 听本 1.1.0+${{ github.run_number }}')
t = t.replace('tag_name: v1.0.0-${{ github.run_number }}', 'tag_name: v1.1.0-${{ github.run_number }}')
if 'R66 语言模型退场验证' not in t:
    t = t.replace('  - name: R65 进度页 v12 验证（小块原貌 / 任务卡删除 / 差一对拍 / 反向注入 / 守恒）\n    run: python3 scripts/r65_verify.py',
        '  - name: R66 语言模型退场验证（UI 零残留 / 三列四步 / 恒直接朗读 / 反向注入 / 守恒）\n    run: python3 scripts/r66_verify.py')
wr(p, t); n[0] += 1

# ═══ 7. 后置清理（中性化之后的形态） ═══
p = os.path.join(J, 'AppChrome.kt')
rep(p, '    NAV_LLM to "文本处理",\n', '')
p = os.path.join(J, 'MainActivity.kt')
t = rd(p)
t = re.sub(r'^ {20,}"(?:语言模型|文本处理)" -> "https://hf-mirror\.com/kuronekkko/[^"]*"\n',
           '', t, flags=re.M)
t = t.replace('Producer.start(c, book, sel, directRead = directMode, config = cfg)',
              'Producer.start(c, book, sel, directRead = true, config = cfg)')
t = t.replace('private enum class ProduceMode { ANNOTATE, DIRECT }',
              'private enum class ProduceMode { DIRECT }')
t = t.replace('(if (t.direct) ProduceMode.DIRECT else ProduceMode.ANNOTATE)',
              'ProduceMode.DIRECT')
wr(p, t); n[0] += 1
# 诊断页里「分段审计 / 模型原始产出」整块删掉：1.1.0 不再有这一层
cut(p, '    Text("分段审计 · 这次到底标了没有", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)',
       '    Row(Modifier.fillMaxWidth()) {')

print('改动计数:', n[0])
PY_END = None
