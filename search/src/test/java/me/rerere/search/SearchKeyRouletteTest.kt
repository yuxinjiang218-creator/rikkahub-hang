package me.rerere.search

import me.rerere.ai.util.KeyRoulette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SearchKeyRouletteTest {
    @After
    fun resetRoulette() {
        SearchService.keyRoulette = KeyRoulette.default()
    }

    @Test
    fun `comma and whitespace separated keys select one key`() {
        val candidates = setOf("first", "second", "third")

        repeat(50) {
            assertTrue(nextSearchApiKey("first, second\nthird", Uuid.random()) in candidates)
        }
    }

    @Test
    fun `key selection uses the search service instance id`() {
        val roulette = RecordingKeyRoulette()
        val serviceId = Uuid.random()
        SearchService.keyRoulette = roulette

        assertEquals("selected", nextSearchApiKey("first,second", serviceId))
        assertEquals("first,second", roulette.keys)
        assertEquals(serviceId.toString(), roulette.providerId)
    }

    private class RecordingKeyRoulette : KeyRoulette {
        var keys: String? = null
        var providerId: String? = null

        override fun next(keys: String, providerId: String): String {
            this.keys = keys
            this.providerId = providerId
            return "selected"
        }
    }
}
