package org.example.llm

data class LlmModel(
    val id: String,
    val displayName: String = id,
)

object LlmModels {
    private val deepSeekModels = listOf(
        LlmModel("deepseek-v4-flash", "DeepSeek V4 Flash"),
    )
    private val openAiModels = listOf(
        LlmModel("gpt-5.6-luna", "GPT-5.6 Luna"),
        LlmModel("gpt-4.1-mini", "GPT-4.1 mini"),
    )

    fun availableFor(kind: LlmKind): List<LlmModel> = when (kind) {
        LlmKind.DEEPSEEK -> deepSeekModels
        LlmKind.OPENAI -> openAiModels
    }

    fun defaultFor(kind: LlmKind): String = availableFor(kind).first().id

    fun normalize(kind: LlmKind, model: String?): String = model
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: defaultFor(kind)
}
