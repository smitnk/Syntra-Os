package com.syntra.os.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/**
 * App-private JSON files (backup disabled in the manifest).
 * shortcut: API keys sit unencrypted in app-private storage; move to Android Keystore before a public release.
 */
class Store(private val dir: File) {
    fun <T> load(name: String, ser: KSerializer<T>, default: T): T {
        val f = File(dir, "$name.json")
        if (!f.exists()) return default
        return runCatching { json.decodeFromString(ser, f.readText()) }.getOrDefault(default)
    }

    fun <T> save(name: String, ser: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(json.encodeToString(ser, value))
        tmp.renameTo(File(dir, "$name.json")) // atomic replace so a crash never leaves half a file
    }
}
