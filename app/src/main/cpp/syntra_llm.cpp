// JNI bridge: one Engine per loaded GGUF model, so several LFM models can run at the same time.
#include <jni.h>
#include <algorithm>
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
    std::vector<llama_token> cached; // tokens currently in the KV cache (prompt + generated)
    std::vector<llama_token> cp_toks; // checkpoint: stable prompt prefix (system prompt)...
    std::vector<uint8_t> cp_state;   // ...and the saved model state right after it
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

// Reuses the KV cache when the new prompt extends the previous one (the normal agent loop),
// so each step only evaluates the new tokens instead of the whole conversation.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syntra_os_local_LlamaNative_generate(JNIEnv *env, jclass, jlong h, jstring jprompt, jstring jmedia,
                                              jint max_tokens, jobject callback, jint stable_chars) {
    auto *e = reinterpret_cast<Engine *>(h);
    e->stop = false;
    const std::string prompt = jstr(env, jprompt);
    const std::string media = jstr(env, jmedia);
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    llama_memory_t mem = llama_get_memory(e->ctx);
    llama_pos n_past = 0;

    if (!media.empty()) {
        if (!e->mtmd) { throw_rt(env, "this model has no vision/audio projector loaded"); return nullptr; }
        llama_memory_clear(mem, true);
        e->cached.clear();
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
        size_t keep = 0;
        while (keep < e->cached.size() && keep < toks.size() && e->cached[keep] == toks[keep]) keep++;
        if (keep == toks.size() && keep > 0) keep--; // always re-evaluate at least one token to get fresh logits
        if (keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            // Recurrent/hybrid layers (LFM) can't drop a middle span: restore the system-prompt checkpoint instead.
            llama_memory_clear(mem, true);
            keep = 0;
            bool cp_ok = !e->cp_toks.empty() && e->cp_toks.size() < toks.size() &&
                         std::equal(e->cp_toks.begin(), e->cp_toks.end(), toks.begin());
            if (cp_ok && llama_state_seq_set_data(e->ctx, e->cp_state.data(), e->cp_state.size(), 0) > 0) keep = e->cp_toks.size();
            else llama_memory_clear(mem, true);
        }
        e->cached.assign(toks.begin(), toks.begin() + keep);

        // Token count of the stable prefix, so we can checkpoint right after it.
        size_t n_cp = 0;
        if (stable_chars > 0 && stable_chars < (int) prompt.size()) {
            int np = -llama_tokenize(e->vocab, prompt.c_str(), stable_chars, nullptr, 0, true, true);
            std::vector<llama_token> pre(std::max(np, 0));
            if (np > 0 && llama_tokenize(e->vocab, prompt.c_str(), stable_chars, pre.data(), np, true, true) == np &&
                pre.size() <= toks.size() && std::equal(pre.begin(), pre.end(), toks.begin())) n_cp = pre.size();
        }
        bool want_cp = n_cp > keep && !(e->cp_toks.size() == n_cp && std::equal(e->cp_toks.begin(), e->cp_toks.end(), toks.begin()));

        auto eval = [&](size_t from, size_t to) -> bool {
            for (size_t i = from; i < to; i += e->n_batch) {
                int m = std::min(e->n_batch, (int) (to - i));
                if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, m)) != 0) return false;
                e->cached.insert(e->cached.end(), toks.begin() + i, toks.begin() + i + m);
            }
            return true;
        };
        bool ok;
        if (want_cp) {
            ok = eval(keep, n_cp);
            if (ok) {
                e->cp_state.resize(llama_state_seq_get_size(e->ctx, 0));
                e->cp_state.resize(llama_state_seq_get_data(e->ctx, e->cp_state.data(), e->cp_state.size(), 0));
                e->cp_toks.assign(toks.begin(), toks.begin() + n_cp);
            }
            ok = ok && eval(n_cp, toks.size());
        } else ok = eval(keep, toks.size());
        if (!ok) { llama_memory_clear(mem, true); e->cached.clear(); throw_rt(env, "decode failed"); return nullptr; }
        n_past = (llama_pos) toks.size();
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
        if (media.empty()) e->cached.push_back(tok);
        n_past++;
    }
    llama_sampler_free(smpl);
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}
