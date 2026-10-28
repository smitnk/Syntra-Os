package com.syntra.os.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS) // local models on phones are slow
    .build()

private val JSON_TYPE = "application/json".toMediaType()

class HttpResult(val code: Int, val body: String, val headers: Map<String, String>)

suspend fun httpCall(
    url: String,
    method: String = "GET",
    body: JsonElement? = null,
    headers: Map<String, String> = emptyMap(),
): HttpResult = withContext(Dispatchers.IO) {
    val rb = body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_TYPE) }
    val req = Request.Builder().url(url).method(method, rb ?: if (method in setOf("POST", "PUT", "PATCH")) "".toRequestBody(JSON_TYPE) else null)
    headers.forEach { (k, v) -> req.header(k, v) }
    http.newCall(req.build()).execute().use { r ->
        HttpResult(r.code, r.body?.string().orEmpty(), r.headers.associate { it.first.lowercase() to it.second })
    }
}

fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
