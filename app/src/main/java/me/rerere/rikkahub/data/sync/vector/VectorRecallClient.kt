package me.rerere.rikkahub.data.sync.vector

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.VectorRecallConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val VECTOR_JSON_MEDIA_TYPE = "application/json".toMediaType()

class VectorRecallClient(
    httpClient: OkHttpClient,
) {
    private val client = httpClient.newBuilder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun isConfigured(config: VectorRecallConfig): Boolean =
        config.serverUrl.isNotBlank() && config.username.isNotBlank()

    fun handshake(config: VectorRecallConfig): VectorHandshakeResponse {
        return post(
            config = config,
            path = "/api/v1/handshake",
            body = "{}",
        )
    }

    fun diff(config: VectorRecallConfig, request: VectorDiffRequest): VectorDiffResponse {
        return post(
            config = config,
            path = "/api/v1/sync/diff",
            body = JsonInstant.encodeToString(request),
        )
    }

    fun upload(config: VectorRecallConfig, request: VectorUploadRequest): VectorUploadResponse {
        return post(
            config = config,
            path = "/api/v1/sync/upload",
            body = JsonInstant.encodeToString(request),
        )
    }

    fun search(config: VectorRecallConfig, request: VectorSearchRequest): VectorSearchResponse {
        return post(
            config = config,
            path = "/api/v1/recall/search",
            body = JsonInstant.encodeToString(request),
        )
    }

    private inline fun <reified T> post(
        config: VectorRecallConfig,
        path: String,
        body: String,
    ): T {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) throw IOException("Vector recall server URL is empty")
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", Credentials.basic(config.username, config.password))
            .post(body.toRequestBody(VECTOR_JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Vector recall request failed: HTTP ${response.code}")
            }
            return JsonInstant.decodeFromString(responseBody)
        }
    }
}
