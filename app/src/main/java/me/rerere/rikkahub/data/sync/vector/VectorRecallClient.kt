package me.rerere.rikkahub.data.sync.vector

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.VectorRecallConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val VECTOR_JSON_MEDIA_TYPE = "application/json".toMediaType()

class VectorRecallHttpException(
    val code: Int,
) : IOException("Vector recall request failed: HTTP $code")

class VectorRecallClient(
    httpClient: OkHttpClient,
) {
    private val client = httpClient.newBuilder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun isConfigured(config: VectorRecallConfig): Boolean =
        config.serverUrl.isNotBlank() &&
            config.username.isNotBlank() &&
            (config.password.isNotBlank() || config.deviceToken.isNotBlank())

    fun login(config: VectorRecallConfig, request: VectorLoginRequest): VectorLoginResponse {
        return post(
            config = config,
            path = "/api/v1/auth/login",
            body = JsonInstant.encodeToString(request),
            deviceToken = null,
        )
    }

    fun handshake(config: VectorRecallConfig): VectorHandshakeResponse {
        return post(
            config = config,
            path = "/api/v1/handshake",
            body = "{}",
            deviceToken = config.deviceToken,
        )
    }

    fun diff(config: VectorRecallConfig, request: VectorDiffRequest): VectorDiffResponse {
        return post(
            config = config,
            path = "/api/v1/sync/diff",
            body = JsonInstant.encodeToString(request),
            deviceToken = config.deviceToken,
        )
    }

    fun upload(config: VectorRecallConfig, request: VectorUploadRequest): VectorUploadResponse {
        return post(
            config = config,
            path = "/api/v1/sync/upload",
            body = JsonInstant.encodeToString(request),
            deviceToken = config.deviceToken,
        )
    }

    fun deleteConversation(
        config: VectorRecallConfig,
        request: VectorDeleteConversationRequest,
    ): VectorDeleteConversationResponse {
        return post(
            config = config,
            path = "/api/v1/sync/delete",
            body = JsonInstant.encodeToString(request),
            deviceToken = config.deviceToken,
        )
    }

    fun search(config: VectorRecallConfig, request: VectorSearchRequest): VectorSearchResponse {
        return post(
            config = config,
            path = "/api/v1/recall/search",
            body = JsonInstant.encodeToString(request),
            deviceToken = config.deviceToken,
        )
    }

    private inline fun <reified T> post(
        config: VectorRecallConfig,
        path: String,
        body: String,
        deviceToken: String?,
    ): T {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) throw IOException("Vector recall server URL is empty")
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(VECTOR_JSON_MEDIA_TYPE))
        if (!deviceToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $deviceToken")
        }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw VectorRecallHttpException(response.code)
            }
            return JsonInstant.decodeFromString(responseBody)
        }
    }
}
