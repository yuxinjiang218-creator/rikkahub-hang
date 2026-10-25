package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * 模型服务返回的错误。
 *
 * [code] 是 HTTP 状态码，能拿到就带上；流式响应里的错误事件通常没有数字状态码，
 * 这时只能靠 [type] 里的错误类型字符串判断，两者都没有就是纯文字错误。
 *
 * 之所以要把状态码单独留成字段：以前它被拼进 message 字符串里，
 * 重试层拿不到就只能一律不重试，导致 429 限流和 5xx 服务端抖动这种
 * 等两秒就能过去的错误，全都变成直接弹窗。
 */
class HttpException(
    message: String,
    val code: Int? = null,
    val type: String? = null,
) : RuntimeException(message) {
    /**
     * 重发同一个请求有没有可能变好。
     *
     * 三级判定，宁可漏判也不乱重试：
     * 1. 有数字状态码：只认 408（服务端自己超时）、429（限流）、5xx（服务端故障）；
     *    400/401/403/404 这类是请求或账号本身的问题，重发结果不会变，只会让用户多等几秒。
     * 2. 没有数字但有错误类型字符串：认各家通用的那几个临时故障类型。
     * 3. 什么都没匹配上：不重试。未知错误里混着余额不足、key 失效、内容被拒，
     *    乱重试不仅没用，还可能被服务商记成滥用。
     */
    val retryable: Boolean
        get() {
            code?.let { return it in RETRYABLE_CODES || it >= 500 }
            val haystack = listOfNotNull(type, message).joinToString(" ").lowercase()
            return RETRYABLE_HINTS.any { it in haystack }
        }

    private companion object {
        val RETRYABLE_CODES = setOf(408, 429)

        /**
         * 各家临时故障的错误类型关键字。
         *
         * 这些是 OpenAI、Anthropic、Google 都在用的约定写法，不是凭空猜的；
         * 只收“过一会儿重试可能成功”的，容量类、鉴权类一概不收。
         */
        val RETRYABLE_HINTS = listOf(
            "rate_limit",
            "rate limit",
            "ratelimit",
            "overloaded",
            "server_error",
            "internal_error",
            "internal error",
            "service_unavailable",
            "unavailable",
            "timeout",
            "timed out",
            "gateway_timeout",
        )
    }
}

/**
 * 把非 2xx 响应包成带状态码的 [HttpException]。
 *
 * 各 provider 以前各自拼字符串，状态码进了 message 就再也没法用来判断要不要重试。
 */
fun httpError(response: Response, body: String): HttpException {
    val detail = runCatching { Json.parseToJsonElement(body).parseErrorDetail() }.getOrNull()
    val text = detail?.message?.takeIf { it.isNotBlank() && it != body } ?: body
    return HttpException(
        message = "HTTP ${response.code}: ${text.take(HTTP_ERROR_BODY_LIMIT)}",
        code = response.code,
        type = detail?.type,
    )
}

private const val HTTP_ERROR_BODY_LIMIT = 2000

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 错误类型字段先取出来：流式响应里的错误事件没有数字状态码，全靠它判断要不要重试
            val type = listOf("type", "code", "status").firstNotNullOfOrNull { key ->
                (this[key] as? JsonPrimitive)?.contentOrNull
            }
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值，把外层的类型带上
                this[foundField]!!.parseErrorDetail().let {
                    if (it.type == null && type != null) HttpException(it.message.orEmpty(), it.code, type) else it
                }
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this), type = type)
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
