package org.example.indexing

import kotlin.math.sqrt

class DeterministicFakeEmbeddingClient(
    private val dimensions: Int = 128,
    override val model: String = "deterministic-hash-v1",
) : EmbeddingClient {
    override val provider: String = "fake"
    val batches = mutableListOf<List<String>>()

    init {
        require(dimensions > 0)
    }

    override suspend fun embed(texts: List<String>): List<EmbeddingVector> {
        batches += texts.toList()
        return texts.map(::vector)
    }

    private fun vector(text: String): EmbeddingVector {
        val values = FloatArray(dimensions)
        Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase()).forEach { match ->
            val hash = match.value.fold(0x811c9dc5.toInt()) { current, char -> (current xor char.code) * 0x01000193 }
            val index = (hash and Int.MAX_VALUE) % dimensions
            values[index] += if (hash and 1 == 0) 1f else -1f
        }
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) values.indices.forEach { values[it] /= norm }
        return EmbeddingVector(values.toList())
    }
}
