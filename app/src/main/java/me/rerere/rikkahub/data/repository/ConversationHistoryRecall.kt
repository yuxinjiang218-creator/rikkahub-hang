package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import java.util.Locale
import kotlin.uuid.Uuid

const val DEFAULT_HISTORY_SEARCH_LIMIT = 6
const val MAX_HISTORY_SEARCH_LIMIT = 12
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
private const val MIN_ROUTE_QUERY_LENGTH = 2
private const val MAX_SNIPPET_KEY_LENGTH = 500
private const val HISTORY_RRF_K = 60.0
private const val RAW_ROUTE_WEIGHT = 1.0
private const val SEGMENT_ROUTE_WEIGHT = 0.8
private const val TOKEN_ROUTE_WEIGHT = 0.65

enum class HistorySearchRoute {
    RAW,
    SEGMENT,
    TOKEN,
}

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
    override fun toString(): String = "$conversationId:$nodeId:$messageId"
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
        ConversationHistoryRef(
            conversationId = Uuid.parse(parts[0]),
            nodeId = Uuid.parse(parts[1]),
            messageId = Uuid.parse(parts[2]),
        )
    }.getOrNull()
}

fun normalizeHistorySearchLimit(limit: Int?): Int =
    (limit ?: DEFAULT_HISTORY_SEARCH_LIMIT).coerceIn(1, MAX_HISTORY_SEARCH_LIMIT)

fun normalizeHistoryReadBefore(before: Int?): Int =
    (before ?: DEFAULT_HISTORY_READ_BEFORE).coerceIn(0, MAX_HISTORY_READ_BEFORE)

fun normalizeHistoryReadAfter(after: Int?): Int =
    (after ?: DEFAULT_HISTORY_READ_AFTER).coerceIn(0, MAX_HISTORY_READ_AFTER)

fun normalizeHistoryReadRefs(refs: List<String>): List<String> =
    refs.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_HISTORY_READ_REFS)

fun parseHistoryRole(raw: String?): MessageRole? = when (raw?.trim()?.lowercase()) {
    null, "", "any" -> null
    "user" -> MessageRole.USER
    "assistant" -> MessageRole.ASSISTANT
    else -> null
}

fun isValidHistoryRole(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
    "user", "assistant", "any" -> true
    else -> false
}

fun buildHistoryQueryPlan(rawQuery: String): HistoryQueryPlan {
    val normalizedRawQuery = rawQuery.trim().replace(Regex("\\s+"), " ")
    if (normalizedRawQuery.isEmpty()) {
        return HistoryQueryPlan(
            rawQuery = "",
            segmentQueries = emptyList(),
            tokenQueries = emptyList(),
        )
    }

    val compacted = normalizeHistoryRouteQuery(normalizedRawQuery)
    val segmentQueries = linkedSetOf<String>()
    if (compacted.isNotBlank() && compacted != normalizedRawQuery) {
        segmentQueries += compacted
    }
    splitHistorySegments(normalizedRawQuery).forEach(segmentQueries::add)
    splitHistorySegments(compacted).forEach(segmentQueries::add)

    return HistoryQueryPlan(
        rawQuery = normalizedRawQuery,
        segmentQueries = segmentQueries
            .asSequence()
            .map(::sanitizeRouteQuery)
            .filter { it.isNotBlank() && it != normalizedRawQuery }
            .take(MAX_SEGMENT_ROUTE_QUERIES)
            .toList(),
        tokenQueries = extractHistoryTokens(compacted)
            .take(MAX_TOKEN_ROUTE_QUERIES),
    )
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
    if (route == HistorySearchRoute.RAW) {
        return routeResults.first().map { result ->
            HistoryRouteCandidate(
                result = result,
                route = route,
                routeHits = 1,
            )
        }
    }

    val aggregates = linkedMapOf<String, RouteAggregate>()
    routeResults.forEachIndexed { queryIndex, results ->
        results.forEachIndexed { rankIndex, result ->
            val key = historyResultKey(result)
            val aggregate = aggregates.getOrPut(key) { RouteAggregate(result = result) }
            aggregate.result = chooseBetterResult(aggregate.result, result)
            aggregate.bestRank = minOf(aggregate.bestRank, rankIndex + 1)
            aggregate.bestQueryIndex = minOf(aggregate.bestQueryIndex, queryIndex)
            aggregate.routeHits += 1
        }
    }

    val minimumMatches = if (route == HistorySearchRoute.TOKEN) {
        minimumHistoryTokenMatches(totalTokenCount)
    } else {
        1
    }

    return aggregates.values
        .asSequence()
        .filter { it.routeHits >= minimumMatches }
        .sortedWith(
            compareByDescending<RouteAggregate> { it.routeHits }
                .thenBy { it.bestRank }
                .thenBy { it.bestQueryIndex }
                .thenByDescending { it.result.createdAt }
        )
        .map { aggregate ->
            HistoryRouteCandidate(
                result = aggregate.result,
                route = route,
                routeHits = aggregate.routeHits,
                matchedTokenCount = if (route == HistorySearchRoute.TOKEN) aggregate.routeHits else 0,
            )
        }
        .toList()
}

fun mergeHistoryRouteCandidates(
    rawCandidates: List<HistoryRouteCandidate>,
    segmentCandidates: List<HistoryRouteCandidate>,
    tokenCandidates: List<HistoryRouteCandidate>,
): List<MessageSearchResult> {
    val aggregates = linkedMapOf<String, MergeAggregate>()
    mergeRoute(aggregates, rawCandidates, RAW_ROUTE_WEIGHT)
    mergeRoute(aggregates, segmentCandidates, SEGMENT_ROUTE_WEIGHT)
    mergeRoute(aggregates, tokenCandidates, TOKEN_ROUTE_WEIGHT)

    return aggregates.values
        .sortedWith(
            compareByDescending<MergeAggregate> { it.score }
                .thenByDescending { it.hasRawHit }
                .thenByDescending { it.bestTokenMatches }
                .thenByDescending { it.routeCount }
                .thenByDescending { it.result.createdAt }
        )
        .map { it.result }
}

fun selectHistorySearchCandidates(
    results: List<MessageSearchResult>,
    requestedLimit: Int,
    perConversationLimit: Int = HISTORY_SEARCH_PER_CONVERSATION_LIMIT,
): List<MessageSearchResult> {
    val limit = normalizeHistorySearchLimit(requestedLimit)
    val countsByConversation = linkedMapOf<String, Int>()
    return buildList(limit) {
        for (result in results) {
            val currentCount = countsByConversation[result.conversationId] ?: 0
            if (currentCount >= perConversationLimit) continue
            add(result)
            countsByConversation[result.conversationId] = currentCount + 1
            if (size >= limit) break
        }
    }
}

fun historySearchResultRef(result: MessageSearchResult): String =
    "${result.conversationId}:${result.nodeId}:${result.messageId}"

fun historySnippetKey(ref: String, snippet: String): String? {
    val normalizedSnippet = snippet
        .replace("[", "")
        .replace("]", "")
        .replace("...", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.ROOT)
        .take(MAX_SNIPPET_KEY_LENGTH)
    if (ref.isBlank() || normalizedSnippet.isBlank()) return null
    return "$ref:$normalizedSnippet"
}

fun filterNewHistorySearchResults(
    results: List<MessageSearchResult>,
    seenSnippetKeys: Set<String>,
): List<MessageSearchResult> {
    if (seenSnippetKeys.isEmpty()) return results
    return results.filter { result ->
        val key = historySnippetKey(
            ref = historySearchResultRef(result),
            snippet = result.snippet,
        )
        key == null || key !in seenSnippetKeys
    }
}

fun groupHistorySearchResultsByConversation(
    results: List<MessageSearchResult>,
    requestedLimit: Int,
    snippetsPerConversation: Int = 2,
): List<ConversationHistorySearchGroup> {
    val limit = normalizeHistorySearchLimit(requestedLimit)
    return results
        .asSequence()
        .withIndex()
        .groupBy(
            keySelector = { it.value.conversationId },
            valueTransform = { it },
        )
        .values
        .map { indexedResults ->
            val ordered = indexedResults.sortedBy { it.index }
            val groupedResults = ordered.map { it.value }
            ConversationHistorySearchGroup(
                conversationId = groupedResults.first().conversationId,
                title = groupedResults.first().title,
                hitCount = groupedResults.size,
                rolesHit = groupedResults.map { it.role }.distinct(),
                latestHitTime = groupedResults.maxOf { it.createdAt },
                topResults = groupedResults.take(snippetsPerConversation.coerceAtLeast(1)),
            ) to ordered.first().index
        }
        .sortedWith(
            compareByDescending<Pair<ConversationHistorySearchGroup, Int>> { it.first.hitCount }
                .thenBy { it.second }
                .thenByDescending { it.first.latestHitTime }
        )
        .take(limit)
        .map { it.first }
}

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
)

private fun mergeRoute(
    aggregates: MutableMap<String, MergeAggregate>,
    routeCandidates: List<HistoryRouteCandidate>,
    weight: Double,
) {
    routeCandidates.forEachIndexed { index, candidate ->
        val key = historyResultKey(candidate.result)
        val aggregate = aggregates.getOrPut(key) { MergeAggregate(result = candidate.result) }
        aggregate.result = chooseBetterResult(aggregate.result, candidate.result)
        aggregate.score += weight / (HISTORY_RRF_K + index + 1.0)
        aggregate.routeCount += 1
        if (candidate.route == HistorySearchRoute.RAW) {
            aggregate.hasRawHit = true
        }
        aggregate.bestTokenMatches = maxOf(aggregate.bestTokenMatches, candidate.matchedTokenCount)
    }
}

private fun normalizeHistoryRouteQuery(rawQuery: String): String =
    rawQuery
        .replace(Regex("[`~!@#$%^&*()+={}\\[\\]|;\"'<>,?]+"), " ")
        .replace(Regex("[，。！？；：、（）【】《》“”‘’]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun splitHistorySegments(rawQuery: String): List<String> =
    rawQuery.split(Regex("[\\s,，。！？；：、()（）\\[\\]{}<>《》]+"))
        .asSequence()
        .map(::sanitizeRouteQuery)
        .filter { it.isNotBlank() }
        .toList()

private fun sanitizeRouteQuery(raw: String): String =
    raw.trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_ROUTE_QUERY_LENGTH)
        .takeIf { it.length >= MIN_ROUTE_QUERY_LENGTH }
        .orEmpty()

private fun extractHistoryTokens(compactedQuery: String): List<String> {
    val tokens = linkedSetOf<String>()
    val candidateTokenRegex = Regex("[A-Za-z0-9_./:\\\\-]{2,64}")
    candidateTokenRegex.findAll(compactedQuery).forEach { match ->
        sanitizeStructuredToken(match.value)?.let(tokens::add)
    }
    return tokens.toList()
}

private fun sanitizeStructuredToken(raw: String): String? {
    val hasStructure = raw.any(Char::isDigit) || raw.any { it in "._:/-\\" }
    if (!hasStructure) return null
    return sanitizeToken(raw)
}

private fun sanitizeToken(raw: String): String? {
    val normalized = raw.trim().lowercase(Locale.ROOT)
    if (normalized.length < MIN_ROUTE_QUERY_LENGTH) return null
    if (normalized.length > MAX_ROUTE_QUERY_LENGTH) return null
    return normalized
}

private fun historyResultKey(result: MessageSearchResult): String =
    "${result.conversationId}:${result.nodeId}:${result.messageId}"

private fun chooseBetterResult(current: MessageSearchResult, candidate: MessageSearchResult): MessageSearchResult =
    when {
        candidate.createdAt > current.createdAt -> candidate
        candidate.createdAt == current.createdAt && candidate.snippet.length > current.snippet.length -> candidate
        else -> current
    }
