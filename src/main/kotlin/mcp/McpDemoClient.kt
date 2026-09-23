package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    val gateway = LocalMcpGateway()

    return try {
        stage = "establishing the MCP connection"
        val tools = gateway.listTools().sortedBy { it.name }
        println("MCP connection established: $MCP_DEMO_SERVER_NAME $MCP_DEMO_VERSION")

        stage = "requesting tools/list"
        check(tools.isNotEmpty()) { "The MCP server returned an empty tool list" }

        val returnedNames = tools.mapTo(mutableSetOf()) { it.name }
        check(returnedNames.containsAll(MCP_DEMO_TOOL_NAMES)) {
            "The MCP server did not return expected tools: ${MCP_DEMO_TOOL_NAMES - returnedNames}"
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
        println("MCP tool discovery and call verified successfully.")
        0
    } catch (error: Exception) {
        System.err.println("MCP demo failed while $stage: ${error.message ?: error::class.simpleName}")
        1
    } finally {
        runCatching { gateway.close() }
            .onFailure { System.err.println("Failed to close MCP gateway cleanly: ${it.message}") }
    }
}
