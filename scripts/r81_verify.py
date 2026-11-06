# -*- coding: utf-8 -*-
"""R81 · 模型页重排验证：正向 / 旧形态零残留 / 守恒 / 结构 / 反向注入。

守的是四件事：缺件态只有一张主卡且主按钮唯一、就绪态不与缺件态同构、
复制地址收成次级入口、ModelStore 与两条下载直链一行不动。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
LINK = ROOT / "docs/R81_模型页重排_联动表.md"
CI = ROOT / ".github/workflows/ci.yml"

POS = [
    'Text("‹ 返回", fontSize = 12.sp, color = Primary)',
    'Text(if (allReady) "已就绪 · 2 份" else "还缺 ${missing.size} 份"',
    'Text("两份文件都在", fontWeight = FontWeight.Bold, fontSize = 16.sp)',
    'Text("还差 ${missing.size} 份文件", fontWeight = FontWeight.Bold, fontSize = 16.sp)',
    'Text("缺件就出不了声，两份都要放进 App 的模型目录。", fontSize = 11.5.sp, color = Sub)',
    'Text("回书架，导入一本书", fontSize = 15.sp)',
    'Text("导入模型文件", fontSize = 15.sp)',
    'Text("需要：$name", fontSize = 10.sp, color = Sub, maxLines = 1)',
    'Text("复制地址 ›", fontSize = 11.5.sp, color = Primary)',
    'Text("重新导入某个文件 ›", fontSize = 11.5.sp, color = Primary)',
    'Text("重新下载再导入一次，或先重启 App。", fontSize = 11.5.sp, color = Sub)',
]

BAN = [
    'TextButton(onClick = onBack) { Text("← 返回") }',
    'Text("复制下载地址")',
    'Text(if (allReady) "重新导入某个文件" else "导入模型文件")',
    'Button(onClick = onLibrary, Modifier.fillMaxWidth().height(52.dp))',
    '文件可能没下完整，重新下载再导入一次；也可以先重启 App。',
    'Text(if (f != null) "已导入 ✓" else "还缺", fontSize = 12.sp',
]

KEEP = [
    'private fun ModelScreen(c: Context, refresh: Int,',
    'onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,',
    'val files = remember(refresh) { ModelStore.files(c) }',
    'val missing = remember(refresh) { ModelStore.missing(c) }',
    '"声音主文件" to ModelStore.TALKER',
    '"声音配套文件" to ModelStore.TOKENIZER',
    '"https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf"',
    '"https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf"',
    'ModelStore.delete(c, f); onChanged()',
    'clipboard.setText(AnnotatedString(url))',
    'Screen.MODELS -> ModelScreen(ctx, refresh,',
    'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }',
]

def model_body(text):
    s = text.index("// ── ⑤b 模型页")
    e = text.index("// ── ⑥ 调音台", s)
    return text[s:e]

def check(files):
    errs = []
    m = files["main"]
    for s in POS:
        if s not in m: errs.append("缺正向: " + s[:54])
    for s in BAN:
        if s in m: errs.append("旧形态残留: " + s[:54])
    for s in KEEP:
        if s not in m: errs.append("守恒丢失: " + s[:54])
    try:
        b = model_body(m)
        if b.count("CardBox(Modifier.padding(bottom = 10.dp))") != 2:
            errs.append("主卡形态异常（应为缺件/就绪各一张）")
        if b.count('Text("导入模型文件"') != 1:
            errs.append("导入主按钮不唯一")
        if (b.count('Button(onClick = onPick, enabled = importing == null,') != 1
                or b.count('TextButton(onClick = onPick, enabled = importing == null)') != 1):
            errs.append("导入入口形态异常：应为缺件主按钮 1 + 就绪小文字入口 1")
        if b.count("slots.forEach") != 1:
            errs.append("两份文件清单应只存在一处")
        if b.count('TextButton(onClick = onBack)') != 1:
            errs.append("返回入口不唯一")
    except ValueError:
        errs.append("ModelScreen 区块定位失败")
    if not LINK.exists(): errs.append("联动表缺失")
    if "r81_verify.py" not in files["ci"]: errs.append("CI 未挂 r81_verify")
    return errs

def main():
    files = {"main": MAIN.read_text(encoding="utf-8"),
             "ci": CI.read_text(encoding="utf-8")}
    errs = check(files)
    if "--selftest" in sys.argv:
        # 反向注入：把旧同构卡形态追加到文件末尾，必须被拦下
        for bad in ['Text("复制下载地址")',
                    'TextButton(onClick = onBack) { Text("← 返回") }',
                    'Text(if (allReady) "重新导入某个文件" else "导入模型文件")']:
            t = files["main"] + "\n// injected\n" + bad + "\n"
            if not check({"main": t, "ci": files["ci"]}):
                errs.append("反向注入未拦下: " + bad[:40])
        else:
            print("反向注入 3/3 全拦下")
    if errs:
        print("FAIL", len(errs)); [print(" -", e) for e in errs]; sys.exit(1)
    print("PASS · R81 正向 %d / 零残留 %d / 守恒 %d" % (len(POS), len(BAN), len(KEEP)))

if __name__ == "__main__":
    main()
