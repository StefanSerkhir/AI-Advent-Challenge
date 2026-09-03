package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.llm.CompletionOptions
import org.example.llm.CompletionResult
import org.example.llm.LlmClient
import org.example.llm.LlmMessage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TemperatureRunnerTest {

    @Test
    fun `runs same prompt at three temperatures and evaluates at zero`() = runBlocking {
        val progressEvents = mutableListOf<TemperatureProgress>()
        val client = RecordingLlmClient()
        val runner = TemperatureRunner(onProgress = { progressEvents += it }) { client }

        val report = runner.compare("Тестовый запрос")

        assertEquals(4, client.calls.size)
        assertEquals(List(3) { "Тестовый запрос" }, client.calls.take(3).map(Call::prompt))
        assertEquals(
            listOf<Double?>(0.0, 0.7, 1.2, 0.0),
            client.calls.map { it.options.temperature },
        )
        assertEquals(TEMPERATURE_VALUES, report.samples.map(TemperatureSample::temperature))
        assertContains(client.calls.last().prompt, "Точность")
        assertContains(client.calls.last().prompt, "Креативность")
        assertContains(client.calls.last().prompt, "Разнообразие")
        assertContains(client.calls.last().prompt, "единичный прогон")
        assertEquals((1..4).toList(), progressEvents.map(TemperatureProgress::current))
    }

    @Test
    fun `completion options accept only finite temperature from zero to two`() {
        CompletionOptions(temperature = 0.0)
        CompletionOptions(temperature = 2.0)

        assertFailsWith<IllegalArgumentException> { CompletionOptions(temperature = -0.1) }
        assertFailsWith<IllegalArgumentException> { CompletionOptions(temperature = 2.1) }
        assertFailsWith<IllegalArgumentException> { CompletionOptions(temperature = Double.NaN) }
    }

    private class RecordingLlmClient : LlmClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult {
            calls += Call(messages.single().content, options)
            return CompletionResult("ответ-${calls.size}", "stop", null)
        }
    }

    private data class Call(
        val prompt: String,
        val options: CompletionOptions,
    )
}
