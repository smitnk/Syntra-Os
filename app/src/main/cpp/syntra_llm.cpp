// JNI bridge: one Engine per loaded GGUF model, so several LFM models can run at the same time.
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    mtmd_context *mtmd = nullptr;
    int n_batch = 512;
    std::atomic<bool> stop{false};
};

static std::once_flag g_backend;

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static void throw_rt(JNIEnv *env, const std::string &msg) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), msg.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_syntra_os_local_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jstring jmmproj, jint n_ctx, jint n_threads) {
    std::call_once(g_backend, [] { llama_backend_init(); });
    auto *e = new Engine();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    e->model = llama_model_load_from_file(jstr(env, jpath).c_str(), mp);
    if (!e->model) { delete e; throw_rt(env, "failed to load model"); return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = e->n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { llama_model_free(e->model); delete e; throw_rt(env, "failed to create context"); return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    std::string mm = jstr(env, jmmproj);
    if (!mm.empty()) {
        auto mpp = mtmd_context_params_default();
        mpp.use_gpu = false;
        mpp.n_threads = n_threads;
        e->mtmd = mtmd_init_from_file(mm.c_str(), e->model, mpp);
        // A missing projector only disables image/audio input; text keeps working.
    }
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_syntra_os_local_LlamaNative_hasMedia(JNIEnv *, jclass, jlong h) {
    return reinterpret_cast<Engine *>(h)->mtmd != nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_syntra_os_local_LlamaNative_marker(JNIEnv *env, jclass) {
    return env->NewStringUTF(mtmd_default_marker());
}

extern "C" JNIEXPORT void JNICALL
Java_com_syntra_os_local_LlamaNative_stop(JNIEnv *, jclass, jlong h) {
    reinterpret_cast<Engine *>(h)->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_syntra_os_local_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (e->mtmd) mtmd_free(e->mtmd);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

// Stateless generation: clears the KV cache and evaluates the full prompt every call.
// shortcut: no prompt-cache reuse across turns; add KV prefix reuse if long chats feel slow.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syntra_os_local_LlamaNative_generate(JNIEnv *env, jclass, jlong h, jstring jprompt, jstring jmedia,
                                              jint max_tokens, jobject callback) {
    auto *e = reinterpret_cast<Engine *>(h);
    e->stop = false;
    const std::string prompt = jstr(env, jprompt);
    const std::string media = jstr(env, jmedia);
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_pos n_past = 0;

    if (!media.empty()) {
        if (!e->mtmd) { throw_rt(env, "this model has no vision/audio projector loaded"); return nullptr; }
        auto w = mtmd_helper_bitmap_init_from_file(e->mtmd, media.c_str(), false, mtmd_helper_init_opt_default());
        if (!w.bitmap) { throw_rt(env, "could not read media file"); return nullptr; }
        mtmd_input_text txt{prompt.c_str(), prompt.size(), true, true};
        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        const mtmd_bitmap *bms[] = {w.bitmap};
        int rc = mtmd_tokenize(e->mtmd, chunks, &txt, bms, 1);
        if (rc == 0 && (int) mtmd_helper_get_n_pos(chunks) + max_tokens > n_ctx) rc = -100;
        if (rc == 0) rc = mtmd_helper_eval_chunks(e->mtmd, e->ctx, chunks, 0, 0, e->n_batch, true, &n_past);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(w.bitmap);
        if (rc != 0) { throw_rt(env, rc == -100 ? "prompt too long for context" : "media eval failed"); return nullptr; }
    } else {
        int n = -llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> toks(n);
        if (llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), n, true, true) < 0) {
            throw_rt(env, "tokenize failed"); return nullptr;
        }
        if (n + max_tokens > n_ctx) { throw_rt(env, "prompt too long for context"); return nullptr; }
        for (int i = 0; i < n; i += e->n_batch) {
            int m = std::min(e->n_batch, n - i);
            if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, m)) != 0) { throw_rt(env, "decode failed"); return nullptr; }
        }
        n_past = n;
    }

    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jmethodID onPiece = callback ? env->GetMethodID(env->GetObjectClass(callback), "onPiece", "([B)Z") : nullptr;
    std::string out;
    for (int i = 0; i < max_tokens && !e->stop && n_past < n_ctx; i++) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        char buf[256];
        int len = llama_token_to_piece(e->vocab, tok, buf, sizeof(buf), 0, false);
        if (len > 0) {
            out.append(buf, len);
            if (onPiece) {
                jbyteArray arr = env->NewByteArray(len);
                env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(buf));
                jboolean keep = env->CallBooleanMethod(callback, onPiece, arr);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck() || !keep) break;
            }
        }
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
        n_past++;
    }
    llama_sampler_free(smpl);
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}
