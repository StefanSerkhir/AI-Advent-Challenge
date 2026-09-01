package org.example.app

import kotlin.test.Test
import kotlin.test.assertContains

class ResponseControlTest {

    @Test
    fun `controlled prompt keeps original request and adds all constraints`() {
        val prompt = withResponseConstraints(
            prompt = "Объясни корутины Kotlin",
            settings = AppSettings(llmKind = org.example.llm.LlmKind.OPENAI),
        )

        assertContains(prompt, "Объясни корутины Kotlin")
        assertContains(prompt, "количество пунктов: 3")
        assertContains(prompt, "не более 60 слов")
        assertContains(prompt, "<END_OF_RESPONSE>")
    }
}
