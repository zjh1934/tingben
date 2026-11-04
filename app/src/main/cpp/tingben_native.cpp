// R20 · 模型底座 JNI 桥。
//
// 【原则】不实现任何推理算法，只转发两个现成引擎的官方公开 C API：
//   - LLM : llama.cpp  llama.h  (model_load / tokenize / decode / sampler / token_to_piece)
//   - TTS : qwentts.cpp qwen.h  (qt_init / qt_synthesize / qt_audio_free)
// 上游 .so 由 scripts/build_native.sh 交叉编译，见 app/src/main/cpp/CMakeLists.txt。
//
// 【为什么写 WAV 而不是让 Kotlin 转】qt_synthesize 直接返回 24kHz / mono / float PCM，
// 与 CONTEXT §3 硬约束一致；C 侧一次写成 16-bit PCM WAV，避免把 GB 级 float 数组过 JNI。

#include <jni.h>
#include <android/log.h>
#include <algorithm>   // R34: std::min
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>

#include "llama.h"
#include "qwen.h"

#define LOG_TAG "tingben-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────
// LLM 句柄
// ─────────────────────────────────────────────────────────────
struct LlmHandle {
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;
    llama_sampler * smpl  = nullptr;
    std::mutex      mu;     // 同一时刻只允许一个 generate
};

static std::atomic<bool> g_cancel{false};

/** R9 · 让 llama_decode（含 60 秒级 prefill）内部也能被停止打断。 */
static bool llmAbortCb(void *) { return g_cancel.load(); }

static void throwJava(JNIEnv * env, const char * msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg);
}

/**
 * R34 · 把 native 内部每一步写回 EventLog（与 Kotlin 共用同一个落盘文件）。
 * 每条立即 flush，SIGSEGV 直接杀进程时最后一条仍留着 —— 这是定位
 * 「LLM_GENERATE_BEGIN 之后没有 END」的唯一办法。调用失败一律静默，绝不反向搞崩。
 */
static jclass g_eventLogCls = nullptr;
static void note(JNIEnv * env, const char * action, const char * detail) {
    // 后台 Thread 里 FindClass 可能拿不到 app 类加载器 → 首次缓存全局引用
    if (g_eventLogCls == nullptr) {
        jclass local = env->FindClass("com/tingben/app/diag/EventLog");
        if (!local) { env->ExceptionClear(); return; }
        g_eventLogCls = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }
    if (!g_eventLogCls) return;
    jmethodID m = env->GetStaticMethodID(
        g_eventLogCls, "nativeNote", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!m) { env->ExceptionClear(); return; }
    jstring k = env->NewStringUTF("ENGINE");
    jstring a = env->NewStringUTF(action);
    jstring d = env->NewStringUTF(detail);
    env->CallStaticVoidMethod(g_eventLogCls, m, k, a, d);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(k); env->DeleteLocalRef(a); env->DeleteLocalRef(d);
}

// ─────────────────────────────────────────────────────────────
// WAV 写入（24kHz / mono / 16-bit PCM）
// ─────────────────────────────────────────────────────────────
static bool writeWav16(const char * path, const float * samples, int n) {
    if (n <= 0 || !samples) return false;
    const int32_t rate = 24000, ch = 1, bits = 16;
    const int32_t dataBytes = n * 2;
    FILE * f = fopen(path, "wb");
    if (!f) return false;

    unsigned char h[44] = {0};
    auto put32 = [&](int off, uint32_t v) {
        h[off] = v & 0xff; h[off+1] = (v >> 8) & 0xff;
        h[off+2] = (v >> 16) & 0xff; h[off+3] = (v >> 24) & 0xff;
    };
    auto put16 = [&](int off, uint16_t v) { h[off] = v & 0xff; h[off+1] = (v >> 8) & 0xff; };
    memcpy(h, "RIFF", 4);        put32(4, 36 + dataBytes);
    memcpy(h + 8, "WAVE", 4);    memcpy(h + 12, "fmt ", 4);
    put32(16, 16); put16(20, 1); put16(22, ch);
    put32(24, rate); put32(28, rate * ch * 2); put16(32, ch * 2); put16(34, bits);
    memcpy(h + 36, "data", 4);   put32(40, dataBytes);
    if (fwrite(h, 1, 44, f) != 44) { fclose(f); return false; }

    // float [-1,1] → int16，带 clamp
    bool ok = true;
    const int BUF = 4096;
    int16_t buf[BUF];
    int written = 0;
    while (written < n) {
        int m = n - written < BUF ? n - written : BUF;
        for (int i = 0; i < m; ++i) {
            float v = samples[written + i];
            if (v > 1.0f) v = 1.0f; else if (v < -1.0f) v = -1.0f;
            buf[i] = (int16_t)(v * 32767.0f);
        }
        if (fwrite(buf, sizeof(int16_t), m, f) != (size_t)m) { ok = false; break; }
        written += m;
    }
    fclose(f);
    return ok;
}

extern "C" {

// ── 通用 ────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_tingben_app_engine_NativeEngine_nativeVersion(JNIEnv * env, jobject) {
    char buf[256];
    snprintf(buf, sizeof(buf), "llama=%s | qwen-tts=%s | abi=%d",
             llama_version(), qt_version(), QT_ABI_VERSION);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

/** 只在整次制作开始时调一次；停止后标志保持 true，直到下次开始。 */
JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_nativeCancelReset(JNIEnv *, jobject) {
    g_cancel.store(false);
}

// ── LLM ─────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_tingben_app_engine_NativeEngine_llmLoad(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads) {
    const char * path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("llmLoad: %s nCtx=%d threads=%d", path, nCtx, nThreads);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                    // CPU 主力（D23：Adreno Vulkan 只做实验）
    auto * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);
    if (!model) {
        LOGE("llama_model_load_from_file failed");
        throwJava(env, "GGUF 加载失败：文件损坏、量化档不支持或内存不足");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = nCtx > 0 ? (uint32_t)nCtx : 8192;
    cp.n_threads = nThreads > 0 ? (uint32_t)nThreads : 4;
    cp.n_threads_batch = cp.n_threads;
    // n_batch 放满整个上下文，让一次 llama_decode 就能提交完整 prompt；
    // n_ubatch 仍是 512，llama.cpp 内部自动分片，compute buffer 不会爆。
    // R34 手工分两批 prefill 实测：第一批 512 token 70 秒、第二批 195 token
    // 反而 115 秒 —— 固定开销按次付，分得越碎越慢。
    cp.n_batch   = cp.n_ctx;
    cp.n_ubatch  = 512;
    auto * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        throwJava(env, "llama_init_from_model 失败（上下文过大？）");
        return 0;
    }

    // R9 · abort callback：prefill 是单次 llama_decode，光靠 decode 循环里的
    // g_cancel 检查要等它整个算完（真机实测 63 秒）才停得下来。
    llama_set_abort_callback(ctx, llmAbortCb, nullptr);

    auto * h = new LlmHandle();
    h->model = model;
    h->ctx   = ctx;
    auto sp  = llama_sampler_chain_default_params();
    h->smpl  = llama_sampler_chain_init(sp);
    // 标注任务要稳定 JSON：贪心优先，temperature 由 Kotlin 侧决定是否放宽
    llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(42));
    LOGI("llmLoad OK handle=%p n_ctx=%u", (void*)h, llama_n_ctx(ctx));
    return (jlong)(intptr_t)h;
}

JNIEXPORT jstring JNICALL
Java_com_tingben_app_engine_NativeEngine_llmGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jPrompt, jint maxTokens, jfloat temperature) {
    auto * h = (LlmHandle *)(intptr_t)handle;
    if (!h || !h->ctx) { throwJava(env, "LLM 未加载"); return nullptr; }
    std::lock_guard<std::mutex> lock(h->mu);
    // R9 · 不在这里复位！之前每次进入 generate 都 g_cancel.store(false)，
    // 于是「停止 → 返回空串 → gate 重试 → 新 generate 把停止标志清零 → 又跑起来」
    // 形成死循环，用户狂点 10 次停止都没用。复位只在整次制作开始时做一次。
    note(env, "NATIVE_ENTER", "llmGenerate");

    // R8 · 同一个 ctx 会被连续调用多次（逐块标注、gate 失败重试）。
    // 不清 KV 的话第二次 prefill 的位置会接着上一次累加，直接把 4096 窗口撑爆：
    // 真机日志 04:49:01 第二次 generate 的 tokens=592 立刻 NATIVE_PREFILL_FAIL。
    // 每次生成都是独立补全，必须从头开始，不需要跨调用保留上下文（跨块上下文由
    // Kotlin 侧把角色表/最近 3 条拼进 prompt 来带）。
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
        // 清完立刻查序列最大位置：-1 才算真正归零。
        // 若不是 -1，说明 llama_decode 的自动位置仍从上一段末尾累加，
        // 第二块 prefill 就会撑爆窗口 —— 这正是「第二段接不上」的硬证据。
        const llama_pos posMax = llama_memory_seq_pos_max(mem, 0);
        char pb[64];
        snprintf(pb, sizeof(pb), "kvCleared=1 posMax=%d", (int)posMax);
        note(env, "NATIVE_KV_RESET", pb);
    }

    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    const int plen = (int)strlen(prompt);
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    const uint32_t nCtx   = llama_n_ctx(h->ctx);
    const uint32_t nBatch = llama_n_batch(h->ctx);   // 已是 n_ctx，一次可提交完整 prompt

    // 先探测需要多少 token（llama_tokenize 返回负值表示容量不足，取绝对值）
    int nMax = llama_tokenize(vocab, prompt, plen, nullptr, 0, false, true);
    if (nMax < 0) nMax = -nMax;
    nMax += 8;
    // R34：异常长度直接拦下，避免 vector 分配巨块内存 → SIGSEGV
    if (nMax <= 0 || nMax > 100000) {
        env->ReleaseStringUTFChars(jPrompt, prompt);
        throwJava(env, "prompt token 数异常，已拒绝生成");
        return nullptr;
    }
    std::vector<llama_token> toks((size_t)nMax);
    int n = llama_tokenize(vocab, prompt, plen, toks.data(), nMax, false, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    if (n <= 0) { throwJava(env, "tokenize 失败"); return nullptr; }
    toks.resize((size_t)n);
    {
        char buf[96];
        snprintf(buf, sizeof(buf), "promptTokens=%d nCtx=%u nBatch=%u", n, nCtx, nBatch);
        note(env, "NATIVE_TOKENIZE_DONE", buf);
    }

    // R34：prompt + 生成余量必须留在 n_ctx 内，否则 llama.cpp 会在解码中途 abort
    const int avail = (int)nCtx - n - 8;
    if (avail < 1) {
        throwJava(env, "prompt 已占满上下文，请把切块调小");
        return nullptr;
    }
    const int limit = std::min(maxTokens > 0 ? maxTokens : 1024, avail);

    // 按 temperature 重建采样链（0 = 贪心，输出合法 JSON 更稳）
    if (h->smpl) llama_sampler_free(h->smpl);
    auto sp = llama_sampler_chain_default_params();
    h->smpl = llama_sampler_chain_init(sp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(h->smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(h->smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(42));
    }

    // R15 · 显式 pos，抄 OfflineLLM 的 LLMInference.cpp（batchAdd 逐 token 设 pos）。
    // llama.h 明确写 llama_batch_get_one 的位置「由 llama_decode 自动追踪」且「avoid using it」：
    // 同一个 ctx 连续生成多块时，自动位置是否随 memory_clear 归零取决于内部状态，
    // 这正是「第一块跑完、第二块 prefill 失败」最该排除的不确定性。
    // 自己填 pos 后，每段补全一定从 0 开始，与上下文内部计数无关。
    {
        char buf[96];
        snprintf(buf, sizeof(buf), "tokens=%zu nCtx=%u nBatch=%u avail=%d limit=%d",
                 toks.size(), nCtx, nBatch, avail, limit);
        note(env, "NATIVE_PREFILL_BEGIN", buf);
        llama_batch batch = llama_batch_init((int32_t)toks.size(), 0, 1);
        for (int j = 0; j < (int)toks.size(); ++j) {
            batch.token[j]      = toks[(size_t)j];
            batch.pos[j]        = (llama_pos)j;
            batch.n_seq_id[j]   = 1;
            batch.seq_id[j][0]  = 0;
            batch.logits[j]     = (j == (int)toks.size() - 1) ? 1 : 0;
        }
        batch.n_tokens = (int32_t)toks.size();
        const int rc = llama_decode(h->ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            if (g_cancel.load()) {
                note(env, "NATIVE_PREFILL_ABORT", "用户停止，prefill 已中止");
                return env->NewStringUTF("");
            }
            note(env, "NATIVE_PREFILL_FAIL", buf);
            throwJava(env, "prefill 失败（上下文溢出或内存不足），请把切块调小");
            return nullptr;
        }
        note(env, "NATIVE_PREFILL_DONE", buf);
    }
    note(env, "NATIVE_DECODE_BEGIN", "开始逐 token 生成");

    std::string out;
    llama_batch next = llama_batch_init(1, 0, 1);
    next.n_tokens = 1;
    next.n_seq_id[0] = 1;
    next.seq_id[0][0] = 0;
    next.logits[0] = 1;

    // R22 · JSON 数组闭合即停。llmGenerate 在本项目里唯一用途就是产出标注数组，
    // 小模型吐完 `]` 常不主动发 EOG，旧实现会一直吐到 maxTokens：尾部被预算腰斩
    // → 四级修复只能保住最后一个完整对象之前的条 → gate 全等失败 → 整块降级直读。
    // 只在「已见到第一个 [」之后启用；字符串内的 ] 不计；全程没出现 [ 时行为不变。
    int jsonDepth = 0;
    bool jsonStarted = false, inStr = false, esc = false;

    for (int i = 0; i < limit; ++i) {
        if (g_cancel.load()) { LOGI("generate cancelled"); break; }
        llama_token id = llama_sampler_sample(h->smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char piece[64];
        int len = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (len > 0) out.append(piece, (size_t)len);

        bool jsonClosed = false;
        for (int p = 0; p < len; ++p) {
            const char ch = piece[p];
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '[') { ++jsonDepth; jsonStarted = true; }
            else if (ch == ']' && jsonStarted) {
                --jsonDepth;
                if (jsonDepth <= 0) { jsonClosed = true; break; }
            }
        }
        if (jsonClosed) {
            char buf[96];
            snprintf(buf, sizeof(buf), "token=%d outChars=%zu · JSON 数组已闭合", i, out.size());
            note(env, "NATIVE_JSON_CLOSE", buf);
            break;
        }

        next.token[0] = id;
        next.pos[0]   = (llama_pos)(toks.size() + i);
        if (llama_decode(h->ctx, next) != 0) {
            note(env, "NATIVE_DECODE_FAIL", "llama_decode 返回非 0");
            break;
        }
        // 8 token 一次心跳：手机 CPU 上一条要十几秒，32 太稀会让人以为死机
        if ((i & 7) == 0) {
            char buf[64];
            snprintf(buf, sizeof(buf), "token=%d outChars=%zu", i, out.size());
            note(env, "NATIVE_DECODE_STEP", buf);
        }
    }
    llama_batch_free(next);
    {
        char buf[96];
        snprintf(buf, sizeof(buf), "outChars=%zu limit=%d", out.size(), limit);
        note(env, "NATIVE_GENERATE_DONE", buf);
    }
    LOGI("llmGenerate done: %d chars", (int)out.size());
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_llmFree(JNIEnv *, jobject, jlong handle) {
    auto * h = (LlmHandle *)(intptr_t)handle;
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->mu);
    if (h->smpl)  llama_sampler_free(h->smpl);
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    LOGI("llmFree done");
}

// ── TTS ─────────────────────────────────────────────────────
static bool ttsCancelCb(void *) { return g_cancel.load(); }

JNIEXPORT jlong JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsLoad(
        JNIEnv * env, jobject, jstring jTalker, jstring jCodec, jstring jDevice) {
    const char * talker = env->GetStringUTFChars(jTalker, nullptr);
    const char * codec  = env->GetStringUTFChars(jCodec, nullptr);
    const char * device = jDevice ? env->GetStringUTFChars(jDevice, nullptr) : nullptr;
    LOGI("ttsLoad: talker=%s codec=%s device=%s", talker, codec, device ? device : "(auto)");

    qt_init_params p;
    qt_init_default_params(&p);
    p.talker_path = talker;
    p.codec_path  = codec;
    p.device      = (device && device[0]) ? device : nullptr;  // NULL = CPU/自动
    p.max_batch   = 1;                 // 手机串行，防热降频（D24）
    p.use_fa      = false;             // CPU 路径不用 flash attention
    p.clamp_fp16  = true;              // Adreno FP16 溢出防护（D23）

    auto * q = qt_init(&p);
    env->ReleaseStringUTFChars(jTalker, talker);
    env->ReleaseStringUTFChars(jCodec, codec);
    if (jDevice) env->ReleaseStringUTFChars(jDevice, device);

    if (!q) {
        const char * err = qt_last_error ? qt_last_error() : "unknown";
        LOGE("qt_init failed: %s", err);
        char msg[512];
        snprintf(msg, sizeof(msg), "TTS 引擎加载失败：%s", err);
        throwJava(env, msg);
        return 0;
    }
    LOGI("ttsLoad OK handle=%p model=%s speakers=%d", (void*)q,
         qt_model_type ? qt_model_type(q) : "?", qt_n_speakers ? qt_n_speakers(q) : -1);
    return (jlong)(intptr_t)q;
}

// R14 · 采样参数全部由 Kotlin 侧 TtsKnobs 传入，默认值来自
// qwentts.cpp/src/sampling-defaults.h，不再硬编码在 JNI 里。
// 顺序：seed, maxNewTokens, temperature, topK, topP, repetitionPenalty,
//       subTemperature, subTopK, subTopP —— 与 NativeEngine.ttsSynthesizeRaw 一一对应。
JNIEXPORT jint JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsSynthesizeRaw(
        JNIEnv * env, jobject, jlong handle, jstring jText, jstring jInstruct,
        jstring jSpeaker, jstring jLang, jstring jOutWav,
        jlong seed, jint maxNewTokens,
        jfloat temperature, jint topK, jfloat topP, jfloat repetitionPenalty,
        jfloat subTemperature, jint subTopK, jfloat subTopP) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q) { throwJava(env, "TTS 未加载"); return -1; }
    g_cancel.store(false);

    const char * text     = env->GetStringUTFChars(jText, nullptr);
    const char * instruct = jInstruct ? env->GetStringUTFChars(jInstruct, nullptr) : nullptr;
    const char * speaker  = jSpeaker ? env->GetStringUTFChars(jSpeaker, nullptr) : nullptr;
    const char * lang     = jLang ? env->GetStringUTFChars(jLang, nullptr) : nullptr;
    const char * outPath  = env->GetStringUTFChars(jOutWav, nullptr);

    qt_tts_params tp;
    qt_tts_default_params(&tp);   // 先拿上游完整默认，再逐字段覆盖，不手搓字段顺序
    tp.text     = text;
    tp.instruct = (instruct && instruct[0]) ? instruct : nullptr;
    tp.lang     = (lang && lang[0]) ? lang : "auto";
    // R23 · A4/E2：custom_voice 靠 speaker 真换声；voicedesign 只吃 instruct。
    // 上游 qt_synthesize 会按 model_type 校验，传错模式返回 QT_STATUS_MODE_INVALID。
    tp.speaker  = (speaker && speaker[0]) ? speaker : nullptr;

    tp.seed                = (int64_t)seed;
    tp.max_new_tokens      = (int)maxNewTokens;
    tp.temperature         = (float)temperature;
    tp.top_k               = (int)topK;
    tp.top_p               = (float)topP;
    tp.repetition_penalty  = (float)repetitionPenalty;
    tp.subtalker_temperature = (float)subTemperature;
    tp.subtalker_top_k       = (int)subTopK;
    tp.subtalker_top_p       = (float)subTopP;

    tp.cancel   = ttsCancelCb;      // 设计系统 §11：长任务必须有可取消入口
    tp.on_chunk = nullptr;          // buffered 模式，一次拿全量 PCM

    qt_audio audio;
    memset(&audio, 0, sizeof(audio));
    int status = qt_synthesize(q, &tp, &audio);

    int ret = status;
    if (status == 0 && audio.n_samples > 0) {
        // -10/-11 是桥层自有码，避开 qwen.h 里 -1..-5 的 qt_status 语义
        if (audio.sample_rate != 24000 || audio.channels != 1) {
            LOGE("unexpected audio format %d Hz / %d ch", audio.sample_rate, audio.channels);
            ret = -11;
        } else if (!writeWav16(outPath, audio.samples, audio.n_samples)) {
            LOGE("writeWav16 failed: %s", outPath);
            ret = -10;
        } else {
            LOGI("ttsSynthesize OK: %d samples -> %s", audio.n_samples, outPath);
        }
    } else if (status != 0) {
        const char * err = qt_last_error ? qt_last_error() : "unknown";
        LOGE("qt_synthesize failed(%d): %s", status, err);
    }
    qt_audio_free(&audio);

    env->ReleaseStringUTFChars(jText, text);
    if (jInstruct) env->ReleaseStringUTFChars(jInstruct, instruct);
    if (jSpeaker)  env->ReleaseStringUTFChars(jSpeaker, speaker);
    if (jLang)     env->ReleaseStringUTFChars(jLang, lang);
    env->ReleaseStringUTFChars(jOutWav, outPath);
    return ret;
}

// R23 · 运行时读出 customvoice 的真实音色表（不硬编码 9 个名字）。
// 返回逗号分隔字符串，Kotlin 侧按 ',' 拆；base / voice_design 返回空串。
JNIEXPORT jstring JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsSpeakers(
        JNIEnv * env, jobject, jlong handle) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q || !qt_n_speakers) return env->NewStringUTF("");
    int n = qt_n_speakers(q);
    if (n <= 0) return env->NewStringUTF("");
    std::string joined;
    for (int i = 0; i < n; ++i) {
        const char * name = qt_speaker_name ? qt_speaker_name(q, i) : nullptr;
        if (!name || !name[0]) continue;
        if (!joined.empty()) joined += ",";
        joined += name;
    }
    return env->NewStringUTF(joined.c_str());
}

JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsFree(JNIEnv *, jobject, jlong handle) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q) return;
    qt_free(q);
    LOGI("ttsFree done");
}

} // extern "C"
