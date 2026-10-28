package com.syntra.os.local

fun interface PieceCallback { fun onPiece(bytes: ByteArray): Boolean }

/** JNI surface of app/src/main/cpp/syntra_llm.cpp (llama.cpp + mtmd). */
object LlamaNative {
    init { System.loadLibrary("syntra_llm") }
    @JvmStatic external fun load(path: String, mmproj: String?, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun hasMedia(handle: Long): Boolean
    @JvmStatic external fun marker(): String
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun generate(handle: Long, prompt: String, media: String?, maxTokens: Int, cb: PieceCallback?): ByteArray
}
