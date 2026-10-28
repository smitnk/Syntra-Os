package com.syntra.os.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Files holding API keys/tokens; encrypted with a hardware-backed Android Keystore key. */
private val SECRET = setOf("settings")

/** App-private JSON files (backup disabled in the manifest). */
class Store(private val dir: File) {
    fun <T> load(name: String, ser: KSerializer<T>, default: T): T {
        val enc = File(dir, "$name.bin")
        val plain = File(dir, "$name.json")
        return runCatching {
            when {
                name in SECRET && enc.exists() -> json.decodeFromString(ser, Crypto.decrypt(enc.readBytes()))
                plain.exists() -> json.decodeFromString(ser, plain.readText()) // also migrates old unencrypted settings
                else -> default
            }
        }.getOrDefault(default)
    }

    @Synchronized
    fun <T> save(name: String, ser: KSerializer<T>, value: T) {
        val text = json.encodeToString(ser, value)
        if (name in SECRET) {
            write(File(dir, "$name.bin"), Crypto.encrypt(text))
            File(dir, "$name.json").delete()
        } else write(File(dir, "$name.json"), text.toByteArray())
    }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f) // atomic replace so a crash never leaves half a file
    }
}

private object Crypto {
    private const val ALIAS = "syntra-store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun encrypt(text: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(text.toByteArray())
    }

    fun decrypt(data: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        return String(c.doFinal(data, 12, data.size - 12))
    }
}
