package me.rerere.rikkahub.data.index

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把归一化后的 float 向量压成 int8，索引里每维只占 1 字节。
 *
 * 全库统一一个缩放系数，所以 cosine 不受影响：分子分母同比例缩放会约掉。
 * 系数按维度解析算出，不需要拿数据标定；用错系数会让召回直接崩掉（实测能到 0.05），
 * 所以 [scaleFor] 的行为由单测守住。
 */
object VectorQuantizer {
    /** 单维允许的最大绝对值（int8 取值范围） */
    const val MAX_QUANT = 127f

    /**
     * 归一化向量在高维空间里单维幅值约 1/√d，取 4 倍标准差作量程上限。
     *
     * 超出量程的分量会被截断，实测占比约 0.006%，可以忽略。
     */
    fun scaleFor(dimension: Int): Float {
        require(dimension > 0) { "dimension must be positive, got $dimension" }
        return 4f / (MAX_QUANT * sqrt(dimension.toFloat()))
    }

    /** 量化：超出量程的分量截断到 ±127 */
    fun quantize(vector: FloatArray, scale: Float): ByteArray {
        require(scale > 0f) { "scale must be positive, got $scale" }
        val out = ByteArray(vector.size)
        for (i in vector.indices) {
            val scaled = (vector[i] / scale).roundToInt()
            out[i] = scaled.coerceIn(-127, 127).toByte()
        }
        return out
    }

    /** 反量化，只用于测试与调试；检索路径直接在 int8 上算距离 */
    fun dequantize(quantized: ByteArray, scale: Float): FloatArray =
        FloatArray(quantized.size) { quantized[it].toInt() * scale }

    /** L2 归一化，零向量原样返回 */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        if (sum <= 0.0) return vector
        val norm = sqrt(sum).toFloat()
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
