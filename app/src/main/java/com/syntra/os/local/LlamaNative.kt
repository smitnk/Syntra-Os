package com.syntra.os.local

fun interface PieceCallback { fun onPiece(bytes: ByteArray): Boolean }

/** JNI surface of app/src/main/cpp/syntra_llm.cpp (llama.cpp + mtmd). */
object LlamaNative {
    init { System.loadLibrary("syntra_llm") }
    @JvmStatic external fun load(path: String, mmproj: String?, nCtx: Int, nThreads: Int, nThreadsBatch: Int, nGpuLayers: Int): Long
    @JvmStatic external fun scoreLabels(handle: Long, prompt: String, labels: Array<String>): FloatArray
    @JvmStatic external fun fork(handle: Long, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun saveCheckpoint(handle: Long, path: String): Boolean
    @JvmStatic external fun loadCheckpoint(handle: Long, path: String): Boolean
    /** [arch, nCtxTrain, nParams, description, chatTemplate] */
    @JvmStatic external fun info(handle: Long): Array<String>
    @JvmStatic external fun applyTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String?
    @JvmStatic external fun hasMedia(handle: Long): Boolean
    @JvmStatic external fun marker(): String
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun generate(handle: Long, prompt: String, media: String?, maxTokens: Int, cb: PieceCallback?, stableChars: Int): ByteArray
}
