package me.rerere.rikkahub.data.sync.vector

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.VectorRecallConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class VectorRecallSyncManagerTest {
    @Test
    fun `toVectorUploadRequest marks only selectIndex message selected and includes metadata`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val firstMessage = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old branch")),
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )
        val selectedMessage = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("selected branch")),
            createdAt = LocalDateTime(2026, 1, 1, 0, 1),
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "Vector",
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(firstMessage, selectedMessage),
                    selectIndex = 1,
                )
            ),
            updateAt = Instant.ofEpochMilli(1234),
        )

        val request = conversation.toVectorUploadRequest()

        assertEquals(assistantId.toString(), request.assistantId)
        assertEquals(conversationId.toString(), request.conversationId)
        assertEquals("Vector", request.conversationTitle)
        assertEquals(1234, request.conversationUpdateAt)
        assertEquals(1, request.nodes.single().selectIndex)
        assertEquals(listOf(false, true), request.nodes.single().messages.map { it.isSelected })
        assertEquals(listOf("old branch", "selected branch"), request.nodes.single().messages.map { it.text })
    }

    @Test
    fun `toVectorUploadRequest keeps full long message text`() {
        val longText = buildString {
            repeat(12_000) { append(('a'.code + it % 26).toChar()) }
            append(" tail marker")
        }
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(longText)),
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "Long",
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(message),
                    selectIndex = 0,
                )
            ),
        )

        val request = conversation.toVectorUploadRequest()

        assertEquals(longText, request.nodes.single().messages.single().text)
    }

    @Test
    fun `VectorSearchResponse ignores optional chunk metadata fields`() {
        val response = JsonInstant.decodeFromString<VectorSearchResponse>(
            """
            {
              "results": [
                {
                  "conversationId": "conversation",
                  "nodeId": "node",
                  "messageId": "message",
                  "conversationTitle": "title",
                  "conversationUpdateAt": 1234,
                  "role": "assistant",
                  "createdAt": 1000,
                  "snippet": "chunk snippet",
                  "score": 0.1,
                  "chunkIndex": 3,
                  "chunkStartOffset": 120,
                  "chunkEndOffset": 300
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("message", response.results.single().messageId)
        assertEquals("chunk snippet", response.results.single().snippet)
    }

    @Test
    fun `VectorDiffResponse decodes deleted cleanup count`() {
        val response = JsonInstant.decodeFromString<VectorDiffResponse>(
            """{"dirty":["conversation"],"deleted":2}"""
        )

        assertEquals(listOf("conversation"), response.dirty)
        assertEquals(2, response.deleted)
    }

    @Test
    fun `VectorRecallConfig decodes old config without device token`() {
        val config = JsonInstant.decodeFromString<VectorRecallConfig>(
            """
            {
              "enabled": true,
              "serverUrl": "https://example.com",
              "username": "boss",
              "password": "secret"
            }
            """.trimIndent()
        )

        assertEquals("", config.deviceToken)
        assertEquals("", config.tokenPrefix)
    }

    @Test
    fun `VectorLoginResponse decodes device token`() {
        val response = JsonInstant.decodeFromString<VectorLoginResponse>(
            """{"deviceToken":"rvk_token","tokenPrefix":"rvk_token","createdAt":1234}"""
        )

        assertEquals("rvk_token", response.deviceToken)
        assertEquals("rvk_token", response.tokenPrefix)
        assertEquals(1234, response.createdAt)
    }

    @Test
    fun `VectorRecallClient login sends no auth and handshake sends bearer token`() {
        val authHeaders = mutableListOf<String?>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                authHeaders += chain.request().header("Authorization")
                val body = when (chain.request().url.encodedPath) {
                    "/api/v1/auth/login" -> """{"deviceToken":"rvk_login","tokenPrefix":"rvk_login","createdAt":1}"""
                    else -> """{"status":"ok","version":"1.0","username":"boss"}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val client = VectorRecallClient(httpClient)
        val config = VectorRecallConfig(
            serverUrl = "https://example.com",
            username = "boss",
            password = "secret",
            deviceToken = "rvk_device",
        )

        client.login(
            config,
            VectorLoginRequest(username = "boss", password = "secret", deviceName = "phone")
        )
        client.handshake(config)

        assertNull(authHeaders[0])
        assertEquals("Bearer rvk_device", authHeaders[1])
    }
}
