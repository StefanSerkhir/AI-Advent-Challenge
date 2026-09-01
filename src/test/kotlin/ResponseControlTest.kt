package org.example

import kotlin.test.Test
import kotlin.test.assertContains

class ResponseControlTest {

    @Test
    fun `controlled prompt keeps original request and adds all constraints`() {
        val prompt = withResponseConstraints("Объясни корутины Kotlin")

        assertContains(prompt, "Объясни корутины Kotlin")
        assertContains(prompt, "ровно 3 пункта")
        assertContains(prompt, "не более 60 слов")
        assertContains(prompt, "<END_OF_RESPONSE>")
    }
}
