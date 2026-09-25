package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.*
import kotlin.test.*

class McpGatewayTest {
    @Test
    fun `real stdio server lists tracker schema and returns structured issue and controlled error`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-gateway-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"))
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

            val tools = gateway.listTools().associateBy(McpTool::name)
            assertTrue(MCP_DEMO_TOOL_NAMES.all { it in tools })
            val createSchema = requireNotNull(tools[SCHEDULER_CREATE_TOOL]).inputSchema
            assertEquals(
                listOf("requestId", "title", "scheduleType", "taskType"),
                createSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
            )

            val requestId = UUID.randomUUID().toString()
            val createArguments = buildJsonObject {
                put("requestId", requestId)
                put("title", "MCP reminder")
                put("scheduleType", "once")
                put("runAt", Instant.now().plusSeconds(3_600).toString())
                put("taskType", "reminder")
                put("reminderText", "Проверить DEMO-101")
            }
            val created = gateway.callTool(SCHEDULER_CREATE_TOOL, createArguments)
            assertFalse(created.isError)
            val scheduleId = Json.parseToJsonElement(created.content).jsonObject["schedules"]!!
                .jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
            val repeated = gateway.callTool(SCHEDULER_CREATE_TOOL, createArguments)
            assertFalse(repeated.isError)
            assertContains(repeated.content, scheduleId)

            val strictError = gateway.callTool(SCHEDULER_CREATE_TOOL, buildJsonObject {
                createArguments.forEach { (key, value) -> put(key, value) }
                put("unexpected", true)
            })
            assertTrue(strictError.isError)
            assertContains(strictError.content, "неизвестные поля")

            val listed = gateway.callTool(SCHEDULER_LIST_TOOL, buildJsonObject {})
            assertFalse(listed.isError)
            assertContains(listed.content, scheduleId)
            val summary = gateway.callTool(SCHEDULER_SUMMARY_TOOL, buildJsonObject { put("scheduleId", scheduleId) })
            assertFalse(summary.isError)
            assertContains(summary.content, "MCP reminder")
            val cancelled = gateway.callTool(SCHEDULER_CANCEL_TOOL, buildJsonObject { put("scheduleId", scheduleId) })
            assertFalse(cancelled.isError)
            assertContains(cancelled.content, "cancelled")
            assertFalse(gateway.callTool(SCHEDULER_CANCEL_TOOL, buildJsonObject { put("scheduleId", scheduleId) }).isError)
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }
}
