package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.index.RecallSource
import java.util.Locale
import kotlin.uuid.Uuid

const val DEFAULT_HISTORY_SEARCH_LIMIT = 8
const val MAX_HISTORY_SEARCH_LIMIT = 30
const val HISTORY_SEARCH_PER_CONVERSATION_LIMIT = 4
const val MAX_HISTORY_SEARCH_FETCH_LIMIT = 72
const val DEFAULT_HISTORY_READ_BEFORE = 1
const val DEFAULT_HISTORY_READ_AFTER = 3
const val MAX_HISTORY_READ_BEFORE = 3
const val MAX_HISTORY_READ_AFTER = 6
const val MAX_HISTORY_READ_REFS = 3

private const val MAX_SEGMENT_ROUTE_QUERIES = 4
private const val MAX_TOKEN_ROUTE_QUERIES = 6
private const val MAX_ROUTE_QUERY_LENGTH = 32
private const val MAX_SNIPPET_KEY_LENGTH = 500
private const val HISTORY_RRF_K = 60.0

enum class HistorySearchRoute { RAW, SEGMENT, TOKEN }

data class HistoryQueryPlan(
    val rawQuery: String,
    val segmentQueries: List<String>,
    val tokenQueries: List<String>,
)

data class HistoryRouteCandidate(
    val result: MessageSearchResult,
    val route: HistorySearchRoute,
    val routeHits: Int,
    val matchedTokenCount: Int = 0,
)

data class ConversationHistoryRef(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
) {
    override fun toString() = "$conversationId:$nodeId:$messageId"
}

data class ConversationHistoryReadMessage(
    val role: MessageRole,
    val createdAt: String,
    val text: String,
    val isMatch: Boolean,
)

data class ConversationHistoryReadResult(
    val ref: String,
    val conversationId: Uuid,
    val title: String,
    val messages: List<ConversationHistoryReadMessage>,
)

data class ConversationHistorySearchGroup(
    val conversationId: String,
    val title: String,
    val hitCount: Int,
    val rolesHit: List<MessageRole>,
    val latestHitTime: java.time.Instant,
    val topResults: List<MessageSearchResult>,
)

fun parseConversationHistoryRef(raw: String): ConversationHistoryRef? {
    val parts = raw.split(":")
    if (parts.size != 3) return null
    return runCatching {
        ConversationHistoryRef(Uuid.parse(parts[0]), Uuid.parse(parts[1]), Uuid.parse(parts[2]))
    }.getOrNull()
}

fun normalizeHistorySearchLimit(limit: Int?): Int =
    (limit ?: DEFAULT_HISTORY_SEARCH_LIMIT).coerceIn(1, MAX_HISTORY_SEARCH_LIMIT)

fun normalizeHistoryReadBefore(before: Int?): Int =
    (before ?: DEFAULT_HISTORY_READ_BEFORE).coerceIn(0, MAX_HISTORY_READ_BEFORE)

fun normalizeHistoryReadAfter(after: Int?): Int =
    (after ?: DEFAULT_HISTORY_READ_AFTER).coerceIn(0, MAX_HISTORY_READ_AFTER)

fun normalizeHistoryReadRefs(refs: List<String>): List<String> =
    refs.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_HISTORY_READ_REFS)

fun parseHistoryRole(raw: String?): MessageRole? = when (raw?.trim()?.lowercase()) {
    "user" -> MessageRole.USER
    "assistant" -> MessageRole.ASSISTANT
    else -> null
}

fun isValidHistoryRole(raw: String?): Boolean = raw?.trim()?.lowercase() in setOf("user", "assistant", "any")

fun buildHistoryQueryPlan(rawQuery: String): HistoryQueryPlan {
    val query = rawQuery.trim().replace(Regex("\\s+"), " ")
    if (query.isEmpty()) return HistoryQueryPlan("", emptyList(), emptyList())
    val compact = query.replace(Regex("[\\p{Punct}\\p{P}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
    val segments = linkedSetOf<String>()
    if (compact.isNotBlank() && compact != query) segments += compact
    segments += query.split(Regex("[\\s\\p{P}]+"))
    segments += compact.split(Regex("[\\s\\p{P}]+"))
    val sanitizedSegments = segments.asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ").take(MAX_ROUTE_QUERY_LENGTH) }
        .filter { it.length >= 2 && it != query }
        .take(MAX_SEGMENT_ROUTE_QUERIES).toList()
    val structuredTokens = Regex("[A-Za-z0-9_./:\\\\-]{2,64}").findAll(query)
        .map { it.value.trim().lowercase(Locale.ROOT) }
        .filter { token ->
            token.length in 2..MAX_ROUTE_QUERY_LENGTH && (token.any(Char::isDigit) || token.any { it in "._:/-\\\\" })
        }
        .distinct().take(MAX_TOKEN_ROUTE_QUERIES).toList()
    return HistoryQueryPlan(query, sanitizedSegments, structuredTokens)
}

fun minimumHistoryTokenMatches(tokenCount: Int): Int = when {
    tokenCount <= 0 -> 0
    tokenCount <= 2 -> 1
    tokenCount <= 5 -> 2
    else -> (tokenCount * 0.4).toInt().coerceAtLeast(3)
}

fun buildRouteCandidates(
    route: HistorySearchRoute,
    routeResults: List<List<MessageSearchResult>>,
    totalTokenCount: Int = 0,
): List<HistoryRouteCandidate> {
    if (routeResults.isEmpty()) return emptyList()
    val aggregates = linkedMapOf<String, RouteAggregate>()
    routeResults.forEachIndexed { queryIndex, results ->
        results.forEachIndexed { rank, result ->
            val key = historyResultKey(result)
            val aggregate = aggregates.getOrPut(key) { RouteAggregate(result) }
            aggregate.result = chooseBetterResult(aggregate.result, result)
            aggregate.bestRank = minOf(aggregate.bestRank, rank + 1)
            aggregate.bestQueryIndex = minOf(aggregate.bestQueryIndex, queryIndex)
            aggregate.routeHits++
        }
    }
    val minimumMatches = if (route == HistorySearchRoute.TOKEN) minimumHistoryTokenMatches(totalTokenCount) else 1
    return aggregates.values.asSequence().filter { it.routeHits >= minimumMatches }
        .sortedWith(compareByDescending<RouteAggregate> { it.routeHits }.thenBy { it.bestRank }.thenBy { it.bestQueryIndex }
            .thenByDescending { it.result.createdAt })
        .map { HistoryRouteCandidate(it.result, route, it.routeHits, if (route == HistorySearchRoute.TOKEN) it.routeHits else 0) }
        .toList()
}

fun mergeHistoryRouteCandidates(
    rawCandidates: List<HistoryRouteCandidate>,
    segmentCandidates: List<HistoryRouteCandidate>,
    tokenCandidates: List<HistoryRouteCandidate>,
): List<MessageSearchResult> {
    val merged = linkedMapOf<String, MergeAggregate>()
    listOf(rawCandidates to 1.0, segmentCandidates to 0.8, tokenCandidates to 0.65).forEach { (candidates, weight) ->
        candidates.forEachIndexed { index, candidate ->
            val aggregate = merged.getOrPut(historyResultKey(candidate.result)) { MergeAggregate(candidate.result) }
            aggregate.result = chooseBetterResult(aggregate.result, candidate.result)
            aggregate.score += weight / (HISTORY_RRF_K + index + 1.0)
            aggregate.routeCount++
            aggregate.sources += candidate.route.toRecallSource()
            aggregate.hasRawHit = aggregate.hasRawHit || candidate.route == HistorySearchRoute.RAW
            aggregate.bestTokenMatches = maxOf(aggregate.bestTokenMatches, candidate.matchedTokenCount)
        }
    }
    return merged.values
        .sortedWith(
            compareByDescending<MergeAggregate> { it.score }
                .thenByDescending { it.hasRawHit }.thenByDescending { it.bestTokenMatches }
                .thenByDescending { it.routeCount }.thenByDescending { it.result.createdAt }
        )
        .map { aggregate -> aggregate.result.copy(matchedBy = aggregate.sources) }
}

fun selectHistorySearchCandidates(
    results: List<MessageSearchResult>,
    requestedLimit: Int,
    perConversationLimit: Int = 4,
): List<MessageSearchResult> {
    val counts = mutableMapOf<String, Int>()
    return results.filter { result ->
        val count = counts[result.conversationId] ?: 0
        if (count >= perConversationLimit) false else {
            counts[result.conversationId] = count + 1
            true
        }
    }.take(normalizeHistorySearchLimit(requestedLimit))
}

private fun HistorySearchRoute.toRecallSource(): RecallSource = when (this) {
    HistorySearchRoute.RAW -> RecallSource.RAW
    HistorySearchRoute.SEGMENT -> RecallSource.SEGMENT
    HistorySearchRoute.TOKEN -> RecallSource.TOKEN
}

fun historySearchResultRef(result: MessageSearchResult) = "${result.conversationId}:${result.nodeId}:${result.messageId}"

fun historySnippetKey(ref: String, snippet: String): String? {
    val normalized = snippet.replace("[", "").replace("]", "").replace("...", " ")
        .replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT).take(MAX_SNIPPET_KEY_LENGTH)
    return if (ref.isBlank() || normalized.isBlank()) null else "$ref:$normalized"
}

fun filterNewHistorySearchResults(results: List<MessageSearchResult>, seenSnippetKeys: Set<String>) =
    if (seenSnippetKeys.isEmpty()) results else results.filter { result ->
        historySnippetKey(historySearchResultRef(result), result.snippet)?.let { it !in seenSnippetKeys } ?: true
    }

fun groupHistorySearchResultsByConversation(
    results: List<MessageSearchResult>,
    requestedLimit: Int,
    snippetsPerConversation: Int = 2,
): List<ConversationHistorySearchGroup> = results.withIndex().groupBy { it.value.conversationId }.values
    .map { indexed ->
        val ordered = indexed.sortedBy { it.index }.map { it.value }
        ConversationHistorySearchGroup(
            conversationId = ordered.first().conversationId,
            title = ordered.first().title,
            hitCount = ordered.size,
            rolesHit = ordered.map { it.role }.distinct(),
            latestHitTime = ordered.maxOf { it.createdAt },
            topResults = ordered.take(snippetsPerConversation.coerceAtLeast(1)),
        ) to indexed.minOf { it.index }
    }.sortedWith(compareByDescending<Pair<ConversationHistorySearchGroup, Int>> { it.first.hitCount }
        .thenBy { it.second }.thenByDescending { it.first.latestHitTime })
    .take(normalizeHistorySearchLimit(requestedLimit)).map { it.first }

private data class RouteAggregate(
    var result: MessageSearchResult,
    var bestRank: Int = Int.MAX_VALUE,
    var bestQueryIndex: Int = Int.MAX_VALUE,
    var routeHits: Int = 0,
)

private data class MergeAggregate(
    var result: MessageSearchResult,
    var score: Double = 0.0,
    var routeCount: Int = 0,
    var hasRawHit: Boolean = false,
    var bestTokenMatches: Int = 0,
    val sources: MutableSet<RecallSource> = mutableSetOf(),
)

private fun historyResultKey(result: MessageSearchResult) = "${result.conversationId}:${result.nodeId}:${result.messageId}"

private fun chooseBetterResult(current: MessageSearchResult, candidate: MessageSearchResult) = when {
    candidate.createdAt > current.createdAt -> candidate
    candidate.createdAt == current.createdAt && candidate.snippet.length > current.snippet.length -> candidate
    else -> current
}
