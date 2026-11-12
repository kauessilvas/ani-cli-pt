package dev.aniclipt.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single connection pool for source and metadata requests. Callers own each response. */
internal object HttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun execute(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        connectTimeoutMillis: Long = 15_000,
        readTimeoutMillis: Long = 20_000,
    ): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            when (method) {
                "POST" -> post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                "HEAD" -> head()
                "GET" -> get()
                else -> error("Método HTTP não suportado: $method")
            }
        }.build()
        val configured = client.newBuilder()
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return configured.newCall(request).execute()
    }

    fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMillis: Long = 15_000,
        readTimeoutMillis: Long = 20_000,
    ): String = execute(url, headers, connectTimeoutMillis = connectTimeoutMillis, readTimeoutMillis = readTimeoutMillis).use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
        response.body?.string().orEmpty()
    }
}
