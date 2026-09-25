package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.*
import kotlin.system.exitProcess

private val schemaJson = Json {
    prettyPrint = true
    explicitNulls = false
}

/**
 * Starts the demo MCP server as a child process, performs the MCP initialize
 * handshake and discovers tools with the protocol's tools/list method.
 */
public fun main() {
    val exitCode = runBlocking { runMcpDemoClient() }
    if (exitCode != 0) exitProcess(exitCode)
}

private suspend fun runMcpDemoClient(): Int {
    var stage = "starting the local MCP server"
    val directory = Files.createTempDirectory("llm-workbench-mcp-demo")
    val outputDirectory = directory.resolve("output")
    val gateway = LocalMcpGateway(directory.resolve(".llm-scheduler-state.json"), outputDirectory)

    return try {
        stage = "establishing the MCP connection"
        val tools = gateway.listTools().sortedBy { it.name }
        println("MCP connection established: $MCP_DEMO_SERVER_NAME $MCP_DEMO_VERSION")

        stage = "requesting tools/list"
        check(tools.isNotEmpty()) { "The MCP server returned an empty tool list" }

        val returnedNames = tools.mapTo(mutableSetOf()) { it.name }
        check(returnedNames == MCP_DEMO_TOOL_NAMES) {
            "Unexpected MCP tool catalog. Missing: ${MCP_DEMO_TOOL_NAMES - returnedNames}; extra: ${returnedNames - MCP_DEMO_TOOL_NAMES}"
        }

        println("tools/list returned ${tools.size} tool(s):")
        tools.forEach { tool ->
            println("- name: ${tool.name}")
            println("  description: ${tool.description}")
            println("  inputSchema:")
            schemaJson.encodeToString(JsonObject.serializer(), tool.inputSchema).lineSequence().forEach { line ->
                println("    $line")
            }
        }
        stage = "running search"
        val search = gateway.callTool(SEARCH_TOOL, buildJsonObject {
            put("query", "композиция MCP-инструментов")
        })
        check(!search.isError) { "search returned an error: ${search.content}" }
        val searchJson = Json.parseToJsonElement(search.content).jsonObject
        val matches = searchJson["matches"]?.jsonArray ?: error("search did not return matches")
        check(matches.isNotEmpty()) { "search returned no matches for the deterministic demo query" }
        println("tools/call search returned ${matches.size} match(es)")

        stage = "summarizing search matches"
        val summarized = gateway.callTool(SUMMARIZE_TOOL, buildJsonObject {
            put("matches", matches)
            put("maxSentences", 3)
        })
        check(!summarized.isError) { "summarize returned an error: ${summarized.content}" }
        val summaryJson = Json.parseToJsonElement(summarized.content).jsonObject
        val summaryText = summaryJson["summary"]?.jsonPrimitive?.content
            ?: error("summarize did not return summary")
        val sourceIds = summaryJson["sourceIds"]?.jsonArray ?: error("summarize did not return sourceIds")
        check(sourceIds.isNotEmpty()) { "summarize lost source provenance" }
        println("tools/call summarize returned sourceIds: ${sourceIds.joinToString()}")

        stage = "saving the pipeline summary"
        val saved = gateway.callTool(SAVE_TO_FILE_TOOL, buildJsonObject {
            put("fileName", "pipeline-summary.md")
            put("content", summaryText)
            put("sourceIds", sourceIds)
        })
        check(!saved.isError) { "save_to_file returned an error: ${saved.content}" }
        val savedJson = Json.parseToJsonElement(saved.content).jsonObject
        check(savedJson["fileName"]?.jsonPrimitive?.content == "pipeline-summary.md") {
            "save_to_file returned an unexpected file name"
        }
        check(savedJson.keys == setOf("fileName", "bytesWritten", "sourceIds") && savedJson["sourceIds"] == sourceIds) {
            "save_to_file returned unexpected metadata or lost provenance"
        }
        check(directory.toAbsolutePath().toString() !in saved.content) {
            "save_to_file exposed an absolute path"
        }
        val savedPath = outputDirectory.resolve("pipeline-summary.md")
        check(Files.exists(savedPath) && Files.readAllBytes(savedPath).contentEquals(summaryText.toByteArray(Charsets.UTF_8))) {
            "save_to_file did not persist the exact summary"
        }
        println("tools/call save_to_file created pipeline-summary.md with exact summary content")

        stage = "calling tracker_get_issue"
        val issue = gateway.callTool(TRACKER_GET_ISSUE_TOOL, buildJsonObject {
            put("issueId", "DEMO-101")
            put("includeComments", true)
        })
        check(!issue.isError) { "tracker_get_issue returned an error: ${issue.content}" }
        check("DEMO-101" in issue.content && "nextAction" in issue.content) {
            "tracker_get_issue returned an unexpected result"
        }
        println("tools/call tracker_get_issue returned: ${issue.content}")
        stage = "creating a persisted scheduler task"
        val created = gateway.callTool(SCHEDULER_CREATE_TOOL, buildJsonObject {
            put("requestId", UUID.randomUUID().toString())
            put("title", "Демонстрационный снимок DEMO-101")
            put("scheduleType", "fixed_interval")
            put("everySeconds", 1800)
            put("startAt", Instant.now().plusSeconds(3600).toString())
            put("taskType", "tracker_snapshot")
            put("issueId", "DEMO-101")
        })
        check(!created.isError) { "scheduler_create returned an error: ${created.content}" }
        val scheduleId = Json.parseToJsonElement(created.content).jsonObject["schedules"]
            ?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: error("scheduler_create did not return schedule id")
        println("tools/call scheduler_create returned schedule: $scheduleId")

        stage = "requesting scheduler summary"
        val summary = gateway.callTool(SCHEDULER_SUMMARY_TOOL, buildJsonObject { put("scheduleId", scheduleId) })
        check(!summary.isError && "Демонстрационный снимок" in summary.content) {
            "scheduler_get_summary returned an unexpected result"
        }
        println("tools/call scheduler_get_summary returned: ${summary.content}")
        gateway.callTool(SCHEDULER_CANCEL_TOOL, buildJsonObject { put("scheduleId", scheduleId) })
        println("MCP tool discovery, search → summarize → save_to_file pipeline, Tracker call and scheduler lifecycle verified successfully.")
        0
    } catch (error: Exception) {
        System.err.println("MCP demo failed while $stage: ${error.message ?: error::class.simpleName}")
        1
    } finally {
        runCatching { gateway.close() }
            .onFailure { System.err.println("Failed to close MCP gateway cleanly: ${it.message}") }
        directory.toFile().deleteRecursively()
    }
}
