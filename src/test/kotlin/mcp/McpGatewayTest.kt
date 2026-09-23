package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class McpGatewayTest {
    @Test
    fun `real stdio server lists tracker schema and returns structured issue and controlled error`() = runBlocking {
        val gateway = LocalMcpGateway()
        try {
            val tool = gateway.listTools().single { it.name == TRACKER_GET_ISSUE_TOOL }
            assertEquals("Gets one issue from the local deterministic Tracker by its identifier.", tool.description)
            assertEquals(listOf("issueId"), tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
            val properties = requireNotNull(tool.inputSchema["properties"]?.jsonObject)
            assertEquals("string", properties["issueId"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertContains(properties["issueId"]?.jsonObject?.get("description")?.jsonPrimitive?.content.orEmpty(), "DEMO-101")
            assertEquals("boolean", properties["includeComments"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals(false, properties["includeComments"]?.jsonObject?.get("default")?.jsonPrimitive?.boolean)
            assertContains(properties["includeComments"]?.jsonObject?.get("description")?.jsonPrimitive?.content.orEmpty(), "Defaults to false")

            val found = gateway.callTool(TRACKER_GET_ISSUE_TOOL, buildJsonObject {
                put("issueId", "DEMO-101")
                put("includeComments", true)
            })
            assertFalse(found.isError)
            val issue = Json.parseToJsonElement(found.content).jsonObject
            assertEquals("DEMO-101", issue["id"]?.jsonPrimitive?.content)
            assertEquals("In Progress", issue["status"]?.jsonPrimitive?.content)
            assertEquals("Ирина Волкова", issue["assignee"]?.jsonPrimitive?.content)
            assertTrue(issue["description"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            assertTrue(issue["nextAction"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            assertEquals(2, issue["comments"]?.jsonArray?.size)

            val missing = gateway.callTool(TRACKER_GET_ISSUE_TOOL, buildJsonObject { put("issueId", "DEMO-404") })
            assertTrue(missing.isError)
            assertEquals("Tracker issue 'DEMO-404' was not found.", missing.content)

            val invalid = gateway.callTool(TRACKER_GET_ISSUE_TOOL, buildJsonObject { put("issueId", "  ") })
            assertTrue(invalid.isError)
            assertEquals("issueId must be a non-empty string.", invalid.content)
            assertFalse(invalid.content.contains("Exception"))
        } finally {
            gateway.close()
        }
    }
}
