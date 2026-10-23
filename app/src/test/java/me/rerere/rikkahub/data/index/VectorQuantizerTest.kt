package me.rerere.rikkahub.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class VectorQuantizerTest {

    private fun unitVector(dimension: Int, random: Random): FloatArray {
        val raw = FloatArray(dimension) { random.nextFloat() * 2f - 1f }
        return VectorQuantizer.normalize(raw)
    }

    @Test
    fun `scale shrinks as dimension grows`() {
        val scales = listOf(128, 256, 512, 1024, 1536).map { VectorQuantizer.scaleFor(it) }
        assertTrue(scales.zipWithNext().all { (a, b) -> b < a })
        assertTrue(scales.all { it > 0f })
    }

    @Test
    fun `quantization stays inside int8 range`() {
        val random = Random(7)
        val vector = unitVector(1024, random)
        val scaled = VectorQuantizer.quantize(vector, VectorQuantizer.scaleFor(1024))
        assertTrue(scaled.all { it >= -127 && it <= 127 })
        assertEquals(1024, scaled.size)
    }

    @Test
    fun `round trip keeps direction`() {
        val random = Random(11)
        val dimension = 1024
        val scale = VectorQuantizer.scaleFor(dimension)
        val original = unitVector(dimension, random)
        val restored = VectorQuantizer.dequantize(VectorQuantizer.quantize(original, scale), scale)
        val similarity = VectorQuantizer.cosine(original, restored)
        assertTrue("cosine should stay close to 1.0, got $similarity", similarity > 0.99f)
    }

    @Test
    fun `quantized vectors keep similarity ordering`() {
        val random = Random(13)
        val dimension = 512
        val scale = VectorQuantizer.scaleFor(dimension)
        val query = unitVector(dimension, random)
        val candidates = (1..400).map { unitVector(dimension, random) }

        val exact = candidates
            .withIndex()
            .sortedByDescending { VectorQuantizer.cosine(query, it.value) }
            .take(10)
            .map { it.index }
            .toSet()

        val quantizedQuery = VectorQuantizer.quantize(query, scale)
        val restoredQuery = VectorQuantizer.dequantize(quantizedQuery, scale)
        val quantized = candidates
            .map { VectorQuantizer.dequantize(VectorQuantizer.quantize(it, scale), scale) }
            .withIndex()
            .sortedByDescending { VectorQuantizer.cosine(restoredQuery, it.value) }
            .take(10)
            .map { it.index }
            .toSet()

        val recall = exact.intersect(quantized).size.toFloat() / exact.size
        assertTrue("recall@10 dropped to $recall", recall >= 0.9f)
    }

    @Test
    fun `normalize produces unit length`() {
        val vector = VectorQuantizer.normalize(FloatArray(64) { it.toFloat() })
        val lengthSquared = vector.sumOf { (it * it).toDouble() }
        assertTrue(abs(lengthSquared - 1.0) < 1e-6)
    }

    @Test
    fun `zero vector survives normalization`() {
        val zero = FloatArray(16)
        assertTrue(VectorQuantizer.normalize(zero).all { it == 0f })
        assertEquals(0f, VectorQuantizer.cosine(zero, zero), 0f)
    }
}
