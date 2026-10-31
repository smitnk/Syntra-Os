// JNI bridge: one Engine per loaded GGUF model, so several LFM models can run at the same time.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

struct Engine {
    bool owns_model = true; // false for forked contexts that share another engine's weights
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    mtmd_context *mtmd = nullptr;
    int n_batch = 512;
    std::atomic<bool> stop{false};
    std::vector<llama_token> cached; // tokens currently in the KV cache (prompt + generated)
    std::vector<llama_token> cp_toks; // checkpoint: stable prompt prefix (system prompt)...
    std::vector<uint8_t> cp_state;   // ...and the saved model state right after it
    bool cp_dirty = false;           // checkpoint changed since last saveCheckpoint
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
Java_com_syntra_os_local_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jstring jmmproj, jint n_ctx, jint n_threads, jint n_threads_batch,
                                          jint n_gpu_layers) {
    // GPU off: hide all Vulkan devices before the backend starts, so a Vulkan build behaves exactly like CPU-only.
    // (Decided at the first load; switching GPU on/off applies after an app restart.)
    std::call_once(g_backend, [n_gpu_layers] { if (n_gpu_layers <= 0) setenv("GGML_VK_VISIBLE_DEVICES", "", 1); llama_backend_init(); });
    auto *e = new Engine();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers; // >0 only when built with Vulkan and the user enabled GPU
    e->model = llama_model_load_from_file(jstr(env, jpath).c_str(), mp);
    if (!e->model) { delete e; throw_rt(env, "failed to load model"); return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = e->n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads_batch; // prompt reading is compute-bound: use every core
    cp.type_k = GGML_TYPE_Q8_0; // half-size K cache: less RAM per chat, same quality in practice (V stays F16: no flash-attn requirement)
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { llama_model_free(e->model); delete e; throw_rt(env, "failed to create context"); return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    std::string mm = jstr(env, jmmproj);
    if (!mm.empty()) {
        auto mpp = mtmd_context_params_default();
        mpp.use_gpu = n_gpu_layers > 0;
        mpp.n_threads = n_threads_batch;
        e->mtmd = mtmd_init_from_file(mm.c_str(), e->model, mpp);
        // A missing projector only disables image/audio input; text keeps working.
    }
    return reinterpret_cast<jlong>(e);
}

// Second context on the same weights (no extra model RAM), for background jobs like memory extraction.
extern "C" JNIEXPORT jlong JNICALL
Java_com_syntra_os_local_LlamaNative_fork(JNIEnv *env, jclass, jlong h, jint n_ctx, jint n_threads) {
    auto *src = reinterpret_cast<Engine *>(h);
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = src->n_batch; cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(src->model, cp);
    if (!ctx) { throw_rt(env, "failed to create context"); return 0; }
    auto *e = new Engine();
    e->owns_model = false; e->model = src->model; e->ctx = ctx; e->vocab = src->vocab;
    return reinterpret_cast<jlong>(e);
}

// Checkpoint persistence: [u32 n_tokens][tokens][state bytes]. Lets the system prompt survive app restarts.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_syntra_os_local_LlamaNative_saveCheckpoint(JNIEnv *env, jclass, jlong h, jstring jpath) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (e->cp_toks.empty() || !e->cp_dirty) return false;
    e->cp_dirty = false;
    FILE *f = fopen(jstr(env, jpath).c_str(), "wb");
    if (!f) return false;
    uint32_t n = (uint32_t) e->cp_toks.size();
    bool ok = fwrite(&n, 4, 1, f) == 1 && fwrite(e->cp_toks.data(), sizeof(llama_token), n, f) == n &&
              fwrite(e->cp_state.data(), 1, e->cp_state.size(), f) == e->cp_state.size();
    fclose(f);
    return ok;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_syntra_os_local_LlamaNative_loadCheckpoint(JNIEnv *env, jclass, jlong h, jstring jpath) {
    auto *e = reinterpret_cast<Engine *>(h);
    FILE *f = fopen(jstr(env, jpath).c_str(), "rb");
    if (!f) return false;
    uint32_t n = 0;
    bool ok = fread(&n, 4, 1, f) == 1 && n > 0 && n < 1000000;
    std::vector<llama_token> toks(ok ? n : 0);
    ok = ok && fread(toks.data(), sizeof(llama_token), n, f) == n;
    std::vector<uint8_t> state;
    if (ok) { uint8_t buf[65536]; size_t r; while ((r = fread(buf, 1, sizeof buf, f)) > 0) state.insert(state.end(), buf, buf + r); }
    fclose(f);
    if (!ok || state.empty()) return false;
    e->cp_toks = std::move(toks); e->cp_state = std::move(state);
    return true;
}

// Classifier-style use (d1 decision model): reads the next-token distribution once and returns, for each label,
// the probability of its first token, normalised over the labels. No text is generated.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_syntra_os_local_LlamaNative_scoreLabels(JNIEnv *env, jclass, jlong h, jstring jprompt, jobjectArray jlabels) {
    auto *e = reinterpret_cast<Engine *>(h);
    const std::string prompt = jstr(env, jprompt);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    if (n <= 0 || llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), n, true, true) < 0 ||
        n >= (int) llama_n_ctx(e->ctx)) { throw_rt(env, "classifier prompt invalid or too long"); return nullptr; }
    for (int i = 0; i < n; i += e->n_batch) {
        int m = std::min(e->n_batch, n - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, m)) != 0) { throw_rt(env, "decode failed"); return nullptr; }
    }
    const float *logits = llama_get_logits_ith(e->ctx, -1);
    const int nl = env->GetArrayLength(jlabels);
    std::vector<float> lg(nl, -1e30f);
    for (int i = 0; i < nl; i++) {
        std::string lab = " " + jstr(env, (jstring) env->GetObjectArrayElement(jlabels, i));
        llama_token t[8];
        int k = llama_tokenize(e->vocab, lab.c_str(), (int) lab.size(), t, 8, false, false);
        if (k > 0) lg[i] = logits[t[0]];
    }
    float mx = *std::max_element(lg.begin(), lg.end()), sum = 0;
    for (float &v : lg) { v = expf(v - mx); sum += v; }
    for (float &v : lg) v /= sum;
    jfloatArray out = env->NewFloatArray(nl);
    env->SetFloatArrayRegion(out, 0, nl, lg.data());
    return out;
}

// Model facts for capability detection: architecture, training context, size, and its built-in chat template.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_syntra_os_local_LlamaNative_info(JNIEnv *env, jclass, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    char arch[128] = {0}, desc[256] = {0};
    llama_model_meta_val_str(e->model, "general.architecture", arch, sizeof arch);
    llama_model_desc(e->model, desc, sizeof desc);
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    std::string vals[5] = { arch, std::to_string(llama_model_n_ctx_train(e->model)), std::to_string(llama_model_n_params(e->model)),
                            desc, tmpl ? tmpl : "" };
    jobjectArray out = env->NewObjectArray(5, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 5; i++) env->SetObjectArrayElement(out, i, env->NewStringUTF(vals[i].c_str()));
    return out;
}

// Formats a conversation with the model's own chat template (llama.cpp's built-in template list).
// Returns null when the template isn't one llama.cpp recognises; the caller then uses ChatML.
extern "C" JNIEXPORT jstring JNICALL
Java_com_syntra_os_local_LlamaNative_applyTemplate(JNIEnv *env, jclass, jlong h, jobjectArray jroles, jobjectArray jcontents) {
    auto *e = reinterpret_cast<Engine *>(h);
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) return nullptr;
    const int n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    size_t total = 0;
    for (int i = 0; i < n; i++) {
        roles[i] = jstr(env, (jstring) env->GetObjectArrayElement(jroles, i));
        contents[i] = jstr(env, (jstring) env->GetObjectArrayElement(jcontents, i));
        total += contents[i].size();
    }
    for (int i = 0; i < n; i++) msgs[i] = { roles[i].c_str(), contents[i].c_str() };
    std::vector<char> buf(total * 2 + 1024);
    int r = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size());
    if (r < 0) return nullptr;
    if (r > (int) buf.size()) { buf.resize(r + 1); r = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size()); }
    return env->NewStringUTF(std::string(buf.data(), r).c_str());
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
    if (e->owns_model) llama_model_free(e->model);
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
                e->cp_dirty = true;
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

// ---- Embeddings (RAG): a small separate model with its own context; text in, L2-normalised vector out. ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_syntra_os_local_LlamaNative_loadEmbedder(JNIEnv *env, jclass, jstring jpath, jint n_threads) {
    std::call_once(g_backend, [] { setenv("GGML_VK_VISIBLE_DEVICES", "", 1); llama_backend_init(); });
    auto *e = new Engine();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    e->model = llama_model_load_from_file(jstr(env, jpath).c_str(), mp);
    if (!e->model) { delete e; throw_rt(env, "failed to load embedding model"); return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = 512; cp.n_batch = 512; cp.n_ubatch = 512; // one chunk per call; chunks are kept under 512 tokens
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.embeddings = true;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { llama_model_free(e->model); delete e; throw_rt(env, "failed to create embedding context"); return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_syntra_os_local_LlamaNative_embed(JNIEnv *env, jclass, jlong h, jstring jtext) {
    auto *e = reinterpret_cast<Engine *>(h);
    std::string text = jstr(env, jtext);
    int n = -llama_tokenize(e->vocab, text.c_str(), (int) text.size(), nullptr, 0, true, true);
    if (n <= 0) n = 1;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(e->vocab, text.c_str(), (int) text.size(), toks.data(), n, true, true) < 0) { throw_rt(env, "tokenize failed"); return nullptr; }
    if ((int) toks.size() > 512) toks.resize(512); // truncate over-long input rather than fail
    llama_memory_clear(llama_get_memory(e->ctx), true);
    if (llama_decode(e->ctx, llama_batch_get_one(toks.data(), (int) toks.size())) != 0) { throw_rt(env, "embed decode failed"); return nullptr; }
    int d = llama_model_n_embd(e->model);
    const float *v = llama_pooling_type(e->ctx) == LLAMA_POOLING_TYPE_NONE ? llama_get_embeddings_ith(e->ctx, -1) : llama_get_embeddings_seq(e->ctx, 0);
    if (!v) { throw_rt(env, "no embeddings (model is not an embedding model?)"); return nullptr; }
    double s = 0; for (int i = 0; i < d; i++) s += v[i] * v[i];
    float inv = s > 0 ? (float) (1.0 / std::sqrt(s)) : 1.f;
    std::vector<float> out(d); for (int i = 0; i < d; i++) out[i] = v[i] * inv;
    jfloatArray arr = env->NewFloatArray(d);
    env->SetFloatArrayRegion(arr, 0, d, out.data());
    return arr;
}

// Load the vision/audio projector on demand (first image), so text-only chats don't pay its RAM.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_syntra_os_local_LlamaNative_attachMedia(JNIEnv *env, jclass, jlong h, jstring jmmproj, jint n_threads, jint n_gpu_layers) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (e->mtmd) return true;
    auto mpp = mtmd_context_params_default();
    mpp.use_gpu = n_gpu_layers > 0;
    mpp.n_threads = n_threads;
    e->mtmd = mtmd_init_from_file(jstr(env, jmmproj).c_str(), e->model, mpp);
    return e->mtmd != nullptr;
}
