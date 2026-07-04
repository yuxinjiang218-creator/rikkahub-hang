package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.repository.ConversationHistoryRef
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.DEFAULT_HISTORY_SEARCH_LIMIT
import me.rerere.rikkahub.data.repository.MAX_HISTORY_READ_REFS
import me.rerere.rikkahub.data.repository.filterNewHistorySearchResults
import me.rerere.rikkahub.data.repository.groupHistorySearchResultsByConversation
import me.rerere.rikkahub.data.repository.historySearchResultRef
import me.rerere.rikkahub.data.repository.historySnippetKey
import me.rerere.rikkahub.data.repository.isValidHistoryRole
import me.rerere.rikkahub.data.repository.normalizeHistoryReadAfter
import me.rerere.rikkahub.data.repository.normalizeHistoryReadBefore
import me.rerere.rikkahub.data.repository.normalizeHistoryReadRefs
import me.rerere.rikkahub.data.repository.normalizeHistorySearchLimit
import me.rerere.rikkahub.data.repository.parseConversationHistoryRef
import me.rerere.rikkahub.data.repository.parseHistoryRole
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    currentConversationId: Uuid,
    messages: List<UIMessage>,
): List<Tool> {
    val seenSnippetKeys = extractSeenHistorySnippetKeys(messages).toMutableSet()
    return listOf(
        Tool(
            name = "recent_chats",
            description = """
                List the user's recent conversations with you to understand their preferences and ongoing topics.
                Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
                Use this when you need quick context about what the user has been discussing lately.
                Only titles and dates are returned; use `conversation_search` to look up the actual content.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                "Maximum number of recent conversations to return (default: 10, max: 30)"
                            )
                        })
                    }
                )
            },
            execute = {
                val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
                val recent = conversationRepo.getRecentConversations(
                    assistantId = assistantId,
                    limit = limit,
                )
                val payload = buildJsonArray {
                    recent.forEach { conversation ->
                        add(buildJsonObject {
                            put("id", conversation.id.toString())
                            put("title", conversation.title.ifBlank { "Untitled" })
                            put("last_chat", conversation.updateAt.toLocalDate())
                        })
                    }
                }
                listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
            }
        ),
        Tool(
            name = "conversation_search",
            description = """
                Search this assistant's messages in the user's other conversations.
                Use this for previous preferences, constraints, plans, decisions, error logs, file names, or old solution notes.
                `role` is required: use user for user facts/preferences, assistant for old explanations/conclusions, or any when unsure.
                Without `conversation_id` / `focus_ref`, results are grouped by conversation. Use a returned conversation_id or ref to continue searching inside one conversation.
                Use focused entities, file names, error codes, and short phrases. Search candidates first, then use `read_chat_history` only when you need original context.
                Older history search/read tool outputs may be cleared as {"cleared":true}; search or read again when you need evidence.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Focused search terms, preferably entities, file names, error codes, or key phrases")
                        })
                        put("role", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("user")
                                add("assistant")
                                add("any")
                            })
                            put("description", "Required: user / assistant / any")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of candidates to return, default 6")
                        })
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional: continue searching inside a specific historical conversation")
                        })
                        put("focus_ref", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional: a ref returned by conversation_search; focuses the search on that conversation")
                        })
                    },
                    required = listOf("query", "role")
                )
            },
            execute = {
                val params = it.jsonObject
                val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                require(query.isNotBlank()) { "query is required" }

                val roleRaw = params["role"]?.jsonPrimitive?.contentOrNull
                require(isValidHistoryRole(roleRaw)) { "role is required and must be one of [user, assistant, any]" }
                val role = parseHistoryRole(roleRaw)
                val limit = normalizeHistorySearchLimit(
                    params["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_HISTORY_SEARCH_LIMIT
                )
                val focusRef = params["focus_ref"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val focusConversationId = when {
                    focusRef.isNotBlank() -> parseFocusConversationIdFromRef(focusRef)
                    else -> parseFocusConversationId(params["conversation_id"]?.jsonPrimitive?.contentOrNull)
                }
                val searchOutcome = conversationRepo.searchAssistantHistoryWithBackend(
                    assistantId = assistantId,
                    keyword = query,
                    currentConversationId = currentConversationId,
                    focusConversationId = focusConversationId,
                    role = role,
                    limit = limit,
                    excludeSnippetKeys = seenSnippetKeys,
                )
                val results = searchOutcome.results
                val newResults = filterNewHistorySearchResults(results, seenSnippetKeys)
                newResults.forEach { result ->
                    historySnippetKey(
                        ref = historySearchResultRef(result),
                        snippet = result.snippet,
                    )?.let(seenSnippetKeys::add)
                }
                val payload = buildJsonObject {
                    put("query", query)
                    put("role", roleRaw?.trim()?.lowercase().orEmpty())
                    put("backend", searchOutcome.backend.value)
                    put("degraded", searchOutcome.degraded)
                    if (focusConversationId == null) {
                        put("mode", "conversation_groups")
                        put("groups", buildJsonArray {
                            groupHistorySearchResultsByConversation(newResults, limit).forEachIndexed { index, group ->
                                add(buildJsonObject {
                                    put("index", index + 1)
                                    put("conversation_id", group.conversationId)
                                    put("conversation_title", group.title)
                                    put("hit_count", group.hitCount)
                                    put("latest_hit_time", group.latestHitTime.toString())
                                    put("roles_hit", buildJsonArray {
                                        group.rolesHit.forEach { role -> add(role.name.lowercase()) }
                                    })
                                    put("top_refs", buildJsonArray {
                                        group.topResults.forEach { result -> add(toHistoryRefString(result)) }
                                    })
                                    put("top_snippets", buildJsonArray {
                                        group.topResults.forEach { result ->
                                            add(buildJsonObject {
                                                put("ref", toHistoryRefString(result))
                                                put("message_role", result.role.name.lowercase())
                                                put("message_time", result.createdAt.toString())
                                                put("snippet", result.snippet)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    } else {
                        put("mode", "messages")
                        put("conversation_id", focusConversationId.toString())
                        put("items", buildJsonArray {
                            newResults.forEachIndexed { index, result ->
                                add(buildJsonObject {
                                    put("index", index + 1)
                                    put("ref", toHistoryRefString(result))
                                    put("conversation_id", result.conversationId)
                                    put("conversation_title", result.title)
                                    put("message_role", result.role.name.lowercase())
                                    put("message_time", result.createdAt.toString())
                                    put("snippet", result.snippet)
                                })
                            }
                        })
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        ),
        Tool(
            name = "read_chat_history",
            description = """
                Read the original chat text around refs returned by `conversation_search`.
                Returns only a small context window, not full conversations or previous tool output.
                Use this after search when the snippet is not enough to verify the evidence.
                Older history search/read tool outputs may be cleared as {"cleared":true}; search or read again when you need evidence.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("refs", buildJsonObject {
                            put("type", "array")
                            put("description", "Refs returned by conversation_search, at most $MAX_HISTORY_READ_REFS")
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                        put("before", buildJsonObject {
                            put("type", "integer")
                            put("description", "How many previous messages to include")
                        })
                        put("after", buildJsonObject {
                            put("type", "integer")
                            put("description", "How many following messages to include")
                        })
                    },
                    required = listOf("refs")
                )
            },
            execute = {
                val params = it.jsonObject
                val refs = normalizeHistoryReadRefs(
                    params["refs"]?.jsonArray?.mapNotNull { item ->
                        item.jsonPrimitive.contentOrNull
                    } ?: emptyList()
                )
                require(refs.isNotEmpty()) { "refs is required" }

                val before = normalizeHistoryReadBefore(params["before"]?.jsonPrimitive?.intOrNull)
                val after = normalizeHistoryReadAfter(params["after"]?.jsonPrimitive?.intOrNull)
                val results = conversationRepo.readAssistantHistory(
                    refs = refs,
                    before = before,
                    after = after,
                )
                val payload = buildJsonObject {
                    put("items", buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("ref", result.ref)
                                put("conversation_id", result.conversationId.toString())
                                put("conversation_title", result.title)
                                put("messages", buildJsonArray {
                                    result.messages.forEach { message ->
                                        add(buildJsonObject {
                                            put("role", message.role.name.lowercase())
                                            put("created_at", message.createdAt)
                                            put("is_match", message.isMatch)
                                            put("text", message.text)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    )
}

private val historyToolJson = Json {
    ignoreUnknownKeys = true
}

internal fun extractSeenHistorySnippetKeys(messages: List<UIMessage>): Set<String> =
    buildSet {
        messages.asSequence()
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName == "conversation_search" || it.toolName == "search_chat_history" }
            .flatMap { it.output.asSequence() }
            .filterIsInstance<UIMessagePart.Text>()
            .forEach { part ->
                runCatching {
                    collectSnippetKeysFromSearchPayload(
                        payload = historyToolJson.parseToJsonElement(part.text),
                        destination = this,
                    )
                }
            }
    }

private fun collectSnippetKeysFromSearchPayload(
    payload: JsonElement,
    destination: MutableSet<String>,
) {
    val root = payload as? JsonObject ?: return
    root["items"]?.jsonArray?.forEach { item ->
        collectSnippetKey(item, destination)
    }
    root["groups"]?.jsonArray?.forEach { group ->
        val groupObject = group as? JsonObject ?: return@forEach
        groupObject["top_snippets"]?.jsonArray?.forEach { snippet ->
            collectSnippetKey(snippet, destination)
        }
    }
}

private fun collectSnippetKey(
    item: JsonElement,
    destination: MutableSet<String>,
) {
    val itemObject = item as? JsonObject ?: return
    val ref = itemObject["ref"]?.jsonPrimitive?.contentOrNull ?: return
    val snippet = itemObject["snippet"]?.jsonPrimitive?.contentOrNull ?: return
    historySnippetKey(ref, snippet)?.let(destination::add)
}

private fun parseFocusConversationIdFromRef(ref: String): Uuid {
    return requireNotNull(parseConversationHistoryRef(ref)?.conversationId) {
        "focus_ref must be a ref returned by conversation_search"
    }
}

private fun parseFocusConversationId(raw: String?): Uuid? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return runCatching { Uuid.parse(value) }.getOrElse {
        error("conversation_id must be a valid UUID")
    }
}

private fun toHistoryRefString(result: MessageSearchResult): String =
    ConversationHistoryRef(
        conversationId = Uuid.parse(result.conversationId),
        nodeId = Uuid.parse(result.nodeId),
        messageId = Uuid.parse(result.messageId),
    ).toString()
