#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <map>

#include "llama.h"
#include "qwen.h"

#define LOG_TAG "tingben-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlmHandle {
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;
    llama_sampler * smpl  = nullptr;
    std::mutex      mu;
};

static std::atomic<bool> g_cancel{false};

static bool llmAbortCb(void *) { return g_cancel.load(); }

static void throwJava(JNIEnv * env, const char * msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg);
}

static jclass g_eventLogCls = nullptr;
static void note(JNIEnv * env, const char * action, const char * detail) {
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

JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_nativeCancelReset(JNIEnv *, jobject) {
    g_cancel.store(false);
}

JNIEXPORT jlong JNICALL
Java_com_tingben_app_engine_NativeEngine_llmLoad(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads) {
    const char * path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("llmLoad: %s nCtx=%d threads=%d", path, nCtx, nThreads);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
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
    cp.n_batch   = cp.n_ctx;
    cp.n_ubatch  = 512;
    auto * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        throwJava(env, "llama_init_from_model 失败（上下文过大？）");
        return 0;
    }

    llama_set_abort_callback(ctx, llmAbortCb, nullptr);

    auto * h = new LlmHandle();
    h->model = model;
    h->ctx   = ctx;
    auto sp  = llama_sampler_chain_default_params();
    h->smpl  = llama_sampler_chain_init(sp);
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
    note(env, "NATIVE_ENTER", "llmGenerate");

    llama_memory_t mem = llama_get_memory(h->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
        const llama_pos posMax = llama_memory_seq_pos_max(mem, 0);
        char pb[64];
        snprintf(pb, sizeof(pb), "kvCleared=1 posMax=%d", (int)posMax);
        note(env, "NATIVE_KV_RESET", pb);
    }

    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    const int plen = (int)strlen(prompt);
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    const uint32_t nCtx   = llama_n_ctx(h->ctx);
    const uint32_t nBatch = llama_n_batch(h->ctx);

    int nMax = llama_tokenize(vocab, prompt, plen, nullptr, 0, false, true);
    if (nMax < 0) nMax = -nMax;
    nMax += 8;
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

    const int avail = (int)nCtx - n - 8;
    if (avail < 1) {
        throwJava(env, "prompt 已占满上下文，请把切块调小");
        return nullptr;
    }
    const int limit = std::min(maxTokens > 0 ? maxTokens : 1024, avail);

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

static bool ttsCancelCb(void *) { return g_cancel.load(); }

struct VoiceRefState {
    std::vector<float> pcm;
    std::string        text;
};
static std::mutex g_refMu;
static std::map<qt_context *, VoiceRefState> g_refs;

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
    p.device      = (device && device[0]) ? device : nullptr;
    p.max_batch   = 1;
    p.use_fa      = false;
    p.clamp_fp16  = true;

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

JNIEXPORT jint JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsSynthesizeRaw(
        JNIEnv * env, jobject, jlong handle, jstring jText, jstring jInstruct,
        jstring jSpeaker, jstring jLang, jstring jOutWav,
        jlong seed, jint maxNewTokens,
        jfloat temperature, jint topK, jfloat topP, jfloat repetitionPenalty,
        jfloat subTemperature, jint subTopK, jfloat subTopP) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q) { throwJava(env, "TTS 未加载"); return -1; }
    std::unique_lock<std::mutex> refLock(g_refMu, std::defer_lock);

    const char * text     = env->GetStringUTFChars(jText, nullptr);
    const char * instruct = jInstruct ? env->GetStringUTFChars(jInstruct, nullptr) : nullptr;
    const char * speaker  = jSpeaker ? env->GetStringUTFChars(jSpeaker, nullptr) : nullptr;
    const char * lang     = jLang ? env->GetStringUTFChars(jLang, nullptr) : nullptr;
    const char * outPath  = env->GetStringUTFChars(jOutWav, nullptr);

    qt_tts_params tp;
    qt_tts_default_params(&tp);
    tp.text     = text;
    tp.lang     = (lang && lang[0]) ? lang : "auto";

    const char * mt = qt_model_type ? qt_model_type(q) : nullptr;
    const bool isBase   = mt && std::strcmp(mt, "base") == 0;
    const bool isCustom = mt && std::strcmp(mt, "custom_voice") == 0;
    if (isBase) {
        tp.instruct = nullptr;
        tp.speaker  = nullptr;
        refLock.lock();
        auto it = g_refs.find(q);
        if (it != g_refs.end() && !it->second.pcm.empty()) {
            tp.ref_audio_24k = it->second.pcm.data();
            tp.ref_n_samples = (int)it->second.pcm.size();
            tp.ref_text = it->second.text.empty() ? nullptr : it->second.text.c_str();
        }
    } else if (isCustom) {
        tp.instruct = (instruct && instruct[0]) ? instruct : nullptr;
        tp.speaker  = (speaker && speaker[0]) ? speaker : nullptr;
    } else {
        tp.instruct = (instruct && instruct[0]) ? instruct : nullptr;
        tp.speaker  = nullptr;
    }

    tp.seed                = (int64_t)seed;
    tp.max_new_tokens      = (int)maxNewTokens;
    tp.temperature         = (float)temperature;
    tp.top_k               = (int)topK;
    tp.top_p               = (float)topP;
    tp.repetition_penalty  = (float)repetitionPenalty;
    tp.subtalker_temperature = (float)subTemperature;
    tp.subtalker_top_k       = (int)subTopK;
    tp.subtalker_top_p       = (float)subTopP;

    LOGI("TTS_PARAMS_APPLIED mode=%s seed=%lld max=%d temp=%.3f top_k=%d top_p=%.3f rep=%.3f "
         "subTemp=%.3f subTopK=%d subTopP=%.3f lang=%s speaker=%s refN=%d instruct=%.40s",
         mt ? mt : "?", (long long)tp.seed, tp.max_new_tokens, tp.temperature, tp.top_k, tp.top_p,
         tp.repetition_penalty, tp.subtalker_temperature, tp.subtalker_top_k,
         tp.subtalker_top_p, tp.lang ? tp.lang : "auto",
         tp.speaker ? tp.speaker : "(null)", tp.ref_n_samples,
         tp.instruct ? tp.instruct : "(null)");

    tp.cancel   = ttsCancelCb;
    tp.on_chunk = nullptr;

    qt_audio audio;
    memset(&audio, 0, sizeof(audio));
    int status = qt_synthesize(q, &tp, &audio);
    if (refLock.owns_lock()) refLock.unlock();

    int ret = status;
    if (status == 0 && audio.n_samples > 0) {
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

JNIEXPORT jboolean JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsSetVoiceRef(
        JNIEnv * env, jobject, jlong handle, jfloatArray jPcm, jstring jRefText) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q || !jPcm) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jPcm);
    if (n <= 0) return JNI_FALSE;
    jfloat * arr = env->GetFloatArrayElements(jPcm, nullptr);
    if (!arr) return JNI_FALSE;
    std::vector<float> pcm((size_t)n);
    for (jsize i = 0; i < n; ++i) pcm[(size_t)i] = arr[i];
    env->ReleaseFloatArrayElements(jPcm, arr, JNI_ABORT);

    std::string txt;
    if (jRefText) {
        const char * s = env->GetStringUTFChars(jRefText, nullptr);
        if (s) { txt = s; env->ReleaseStringUTFChars(jRefText, s); }
    }
    const size_t nchars = txt.size();
    {
        std::lock_guard<std::mutex> lk(g_refMu);
        VoiceRefState & st = g_refs[q];
        st.pcm = std::move(pcm);
        st.text = std::move(txt);
    }
    LOGI("ttsSetVoiceRef: %d samples, refText chars=%zu", (int)n, nchars);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tingben_app_engine_NativeEngine_ttsClearVoiceRef(JNIEnv *, jobject, jlong handle) {
    auto * q = (qt_context *)(intptr_t)handle;
    if (!q) return;
    std::lock_guard<std::mutex> lk(g_refMu);
    g_refs.erase(q);
}

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
    { std::lock_guard<std::mutex> lk(g_refMu); g_refs.erase(q); }
    qt_free(q);
    LOGI("ttsFree done");
}

}
