package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.*
import kotlin.test.*

class McpGatewayTest {
    @Test
    fun `git current branch is read only strict safe and handles detached and non git roots`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-git-branch-test")
        val repository = directory.resolve("repository")
        Files.createDirectories(repository)
        fun git(vararg arguments: String) {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
        }
        git("init", "-b", "feature/developer-help")
        git("config", "user.name", "Fixture User")
        git("config", "user.email", "fixture@example.invalid")
        Files.writeString(repository.resolve("README.md"), "fixture-secret-must-not-leak")
        git("add", "README.md")
        git("commit", "-m", "fixture")
        val gateway = LocalMcpGateway(
            directory.resolve("scheduler.json"),
            directory.resolve("output"),
            repositoryRoot = repository,
        )
        try {
            val tool = gateway.listTools().single { it.name == GIT_CURRENT_BRANCH_TOOL }
            assertEquals(WORKSPACE_SERVER_ID, tool.serverId)
            assertTrue(tool.inputSchema["properties"]!!.jsonObject.isEmpty())

            val branch = gateway.callTool(GIT_CURRENT_BRANCH_TOOL, buildJsonObject {})
            assertFalse(branch.isError)
            val branchJson = Json.parseToJsonElement(branch.content).jsonObject
            assertEquals(true, branchJson["available"]?.jsonPrimitive?.boolean)
            assertEquals("feature/developer-help", branchJson["branch"]?.jsonPrimitive?.content)
            assertEquals(false, branchJson["detached"]?.jsonPrimitive?.boolean)
            assertTrue(branchJson["shortCommit"]?.jsonPrimitive?.content.orEmpty().matches(Regex("[0-9a-f]{12}")))
            assertFalse(branch.content.contains(repository.toAbsolutePath().toString()))
            assertFalse(branch.content.contains("fixture-secret-must-not-leak"))

            val unknown = gateway.callTool(GIT_CURRENT_BRANCH_TOOL, buildJsonObject { put("path", "/tmp") })
            assertTrue(unknown.isError)
            assertContains(unknown.content, "неизвестные поля")
            assertFalse(unknown.content.contains("/tmp"))

            git("checkout", "--detach", "HEAD")
            val detached = Json.parseToJsonElement(
                gateway.callTool(GIT_CURRENT_BRANCH_TOOL, buildJsonObject {}).content,
            ).jsonObject
            assertEquals(true, detached["available"]?.jsonPrimitive?.boolean)
            assertEquals(true, detached["detached"]?.jsonPrimitive?.boolean)
            assertEquals(JsonNull, detached["branch"])
            assertTrue(detached["shortCommit"]?.jsonPrimitive?.content.orEmpty().matches(Regex("[0-9a-f]{12}")))
        } finally {
            gateway.close()
        }

        val plainDirectory = directory.resolve("plain")
        Files.createDirectories(plainDirectory)
        val plainGateway = LocalMcpGateway(
            directory.resolve("plain-scheduler.json"),
            directory.resolve("plain-output"),
            repositoryRoot = plainDirectory,
        )
        try {
            val unavailable = Json.parseToJsonElement(
                plainGateway.callTool(GIT_CURRENT_BRANCH_TOOL, buildJsonObject {}).content,
            ).jsonObject
            assertEquals(false, unavailable["available"]?.jsonPrimitive?.boolean)
            assertEquals(false, unavailable["detached"]?.jsonPrimitive?.boolean)
            assertEquals(JsonNull, unavailable["branch"])
            assertFalse(unavailable.toString().contains(plainDirectory.toAbsolutePath().toString()))
        } finally {
            plainGateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry aggregates catalogs from four independent stdio processes`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-registry-test")
        val processes = mutableListOf<Process>()
        val registrations = listOf(OPERATIONS_SERVER_ID, KNOWLEDGE_SERVER_ID, SUPPORT_SERVER_ID, WORKSPACE_SERVER_ID).map { serverId ->
            localMcpServerRegistration(
                serverId,
                directory.resolve("scheduler.json"),
                directory.resolve("output"),
                processes::add,
            )
        }
        val gateway = LocalMcpGateway(
            directory.resolve("scheduler.json"),
            directory.resolve("output"),
            registrations,
        )
        try {
            gateway.start()
            val tools = gateway.listTools()
            assertEquals(4, processes.map(Process::pid).distinct().size)
            assertTrue(processes.all(Process::isAlive))
            assertEquals(MCP_DEMO_TOOL_NAMES, tools.mapTo(mutableSetOf(), McpTool::name))
            assertEquals(MCP_TOOL_NAMES_BY_SERVER, tools.groupBy(McpTool::serverId)
                .mapValues { (_, serverTools) -> serverTools.mapTo(mutableSetOf(), McpTool::name) })
        } finally {
            gateway.close()
            assertTrue(processes.none(Process::isAlive))
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `real stdio server exposes strict pipeline schemas and passes actual data through all three tools`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-gateway-test")
        val outputDirectory = directory.resolve("output")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), outputDirectory)
        try {
            val tools = gateway.listTools().associateBy(McpTool::name)
            assertEquals(MCP_DEMO_TOOL_NAMES, tools.keys)
            assertEquals(KNOWLEDGE_SERVER_ID, tools.getValue(SEARCH_TOOL).serverId)
            assertEquals(KNOWLEDGE_SERVER_ID, tools.getValue(SUMMARIZE_TOOL).serverId)
            assertEquals(WORKSPACE_SERVER_ID, tools.getValue(SAVE_TO_FILE_TOOL).serverId)
            assertEquals(OPERATIONS_SERVER_ID, tools.getValue(TRACKER_GET_ISSUE_TOOL).serverId)
            assertEquals(SUPPORT_SERVER_ID, tools.getValue(SUPPORT_GET_TICKET_CONTEXT_TOOL).serverId)

            val support = gateway.callTool(SUPPORT_GET_TICKET_CONTEXT_TOOL, buildJsonObject {
                put("ticketId", "TCK-1001")
            })
            assertFalse(support.isError)
            val supportJson = Json.parseToJsonElement(support.content).jsonObject
            assertEquals("TCK-1001", supportJson["ticket"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("USR-1001", supportJson["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("ACTIVE", supportJson["user"]?.jsonObject?.get("accountStatus")?.jsonPrimitive?.content)
            assertEquals(true, supportJson["user"]?.jsonObject?.get("mfaEnabled")?.jsonPrimitive?.boolean)
            assertContains(support.content, "AUTH_CLOCK_SKEW")
            assertFalse(support.content.contains(directory.toAbsolutePath().toString()))

            val missingTicket = gateway.callTool(SUPPORT_GET_TICKET_CONTEXT_TOOL, buildJsonObject {
                put("ticketId", "TCK-9999")
            })
            assertTrue(missingTicket.isError)
            assertContains(missingTicket.content, "не найден")
            val invalidTicket = gateway.callTool(SUPPORT_GET_TICKET_CONTEXT_TOOL, buildJsonObject {
                put("ticketId", "TCK-0")
            })
            assertTrue(invalidTicket.isError)
            assertContains(invalidTicket.content, "неверный формат")

            val searchSchema = requireNotNull(tools[SEARCH_TOOL]).inputSchema
            assertEquals("https://json-schema.org/draft/2020-12/schema", searchSchema["${'$'}schema"]?.jsonPrimitive?.content)
            assertEquals(listOf("query"), searchSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
            val querySchema = searchSchema["properties"]!!.jsonObject["query"]!!.jsonObject
            assertEquals("string", querySchema["type"]?.jsonPrimitive?.content)
            assertEquals(1, querySchema["minLength"]?.jsonPrimitive?.int)
            assertEquals(200, querySchema["maxLength"]?.jsonPrimitive?.int)

            val summarizeSchema = requireNotNull(tools[SUMMARIZE_TOOL]).inputSchema
            assertEquals(listOf("matches"), summarizeSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
            val matchItems = summarizeSchema["properties"]!!.jsonObject["matches"]!!.jsonObject["items"]!!.jsonObject
            assertEquals(false, matchItems["additionalProperties"]?.jsonPrimitive?.boolean)
            assertEquals(
                listOf("id", "title", "content"),
                matchItems["required"]?.jsonArray?.map { it.jsonPrimitive.content },
            )

            val saveSchema = requireNotNull(tools[SAVE_TO_FILE_TOOL]).inputSchema
            assertEquals(
                listOf("fileName", "content"),
                saveSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertContains(
                saveSchema["properties"]!!.jsonObject["fileName"]!!.jsonObject["pattern"]!!.jsonPrimitive.content,
                "A-Za-z0-9",
            )

            val search = gateway.callTool(SEARCH_TOOL, buildJsonObject {
                put("query", "локальные сведения о композиции MCP-инструментов")
            })
            assertFalse(search.isError)
            val searchJson = Json.parseToJsonElement(search.content).jsonObject
            assertEquals("локальные сведения о композиции MCP-инструментов", searchJson["query"]?.jsonPrimitive?.content)
            val matches = searchJson["matches"]!!.jsonArray
            assertEquals(searchJson["matchCount"]?.jsonPrimitive?.int, matches.size)
            assertTrue(matches.isNotEmpty())
            assertEquals("mcp-composition-001", matches.first().jsonObject["id"]?.jsonPrimitive?.content)

            val summarized = gateway.callTool(SUMMARIZE_TOOL, buildJsonObject {
                put("matches", matches)
                put("maxSentences", 3)
            })
            assertFalse(summarized.isError)
            val summaryJson = Json.parseToJsonElement(summarized.content).jsonObject
            val summary = summaryJson["summary"]!!.jsonPrimitive.content
            val sourceIds = summaryJson["sourceIds"]!!.jsonArray
            assertEquals(matches.map { it.jsonObject["id"] }, sourceIds)
            assertEquals(sourceIds.size, summaryJson["processedCount"]?.jsonPrimitive?.int)
            assertContains(summary, matches.first().jsonObject["content"]!!.jsonPrimitive.content)

            val saved = gateway.callTool(SAVE_TO_FILE_TOOL, buildJsonObject {
                put("fileName", "pipeline-summary.md")
                put("content", summary)
                put("sourceIds", sourceIds)
            })
            assertFalse(saved.isError)
            val savedJson = Json.parseToJsonElement(saved.content).jsonObject
            assertEquals("pipeline-summary.md", savedJson["fileName"]?.jsonPrimitive?.content)
            assertEquals(summary.toByteArray(Charsets.UTF_8).size, savedJson["bytesWritten"]?.jsonPrimitive?.int)
            assertEquals(sourceIds, savedJson["sourceIds"])
            assertFalse(saved.content.contains(directory.toAbsolutePath().toString()))
            assertEquals(summary, Files.readString(outputDirectory.resolve("pipeline-summary.md")))
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gateway starts independent real processes and rejects duplicate discovered tool names`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-collision-test")
        val processes = mutableListOf<Process>()
        val registrations = listOf("knowledge-a", "knowledge-b").map { registrationId ->
            McpServerRegistration(registrationId) {
                startServerProcess(
                    KNOWLEDGE_SERVER_ID,
                    directory.resolve("scheduler.json"),
                    directory.resolve("output"),
                ).also(processes::add)
            }
        }
        val gateway = LocalMcpGateway(
            schedulerStateFile = directory.resolve("scheduler.json"),
            outputDirectory = directory.resolve("output"),
            registrations = registrations,
        )
        try {
            gateway.start()
            assertEquals(2, processes.map(Process::pid).distinct().size)
            assertTrue(processes.all(Process::isAlive))
            val error = assertFailsWith<McpCatalogException> { gateway.listTools() }
            assertContains(error.message.orEmpty(), "search")
            assertContains(error.message.orEmpty(), "knowledge-a")
            assertContains(error.message.orEmpty(), "knowledge-b")
        } finally {
            gateway.close()
            assertTrue(processes.none(Process::isAlive))
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `dead server session reconnects without restarting healthy owners`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-reconnect-test")
        val processes = mutableMapOf<String, MutableList<Process>>()
        val registrations = listOf(OPERATIONS_SERVER_ID, KNOWLEDGE_SERVER_ID, WORKSPACE_SERVER_ID).map { serverId ->
            localMcpServerRegistration(
                serverId,
                directory.resolve("scheduler.json"),
                directory.resolve("output"),
            ) { process -> processes.getOrPut(serverId, ::mutableListOf).add(process) }
        }
        val gateway = LocalMcpGateway(
            directory.resolve("scheduler.json"),
            directory.resolve("output"),
            registrations,
        )
        try {
            gateway.listTools()
            val healthyPids = listOf(OPERATIONS_SERVER_ID, WORKSPACE_SERVER_ID)
                .associateWith { requireNotNull(processes[it]).single().pid() }
            requireNotNull(processes[KNOWLEDGE_SERVER_ID]).single().destroyForcibly().waitFor()

            val result = gateway.callTool(SEARCH_TOOL, buildJsonObject { put("query", "композиция MCP") })

            assertFalse(result.isError)
            assertEquals(2, requireNotNull(processes[KNOWLEDGE_SERVER_ID]).size)
            healthyPids.forEach { (serverId, pid) ->
                assertEquals(pid, requireNotNull(processes[serverId]).single().pid())
                assertTrue(requireNotNull(processes[serverId]).single().isAlive)
            }
        } finally {
            gateway.close()
            assertTrue(processes.values.flatten().none(Process::isAlive))
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pipeline validation rejects malformed arguments and cannot escape output root`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-validation-test")
        val outputDirectory = directory.resolve("output")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), outputDirectory)
        try {
            suspend fun assertControlledError(tool: String, arguments: JsonObject, expected: String) {
                val result = gateway.callTool(tool, arguments)
                assertTrue(result.isError)
                assertContains(result.content, expected)
                assertFalse(result.content.contains("Exception"))
                assertFalse(result.content.contains("at org.example"))
            }

            assertControlledError(SEARCH_TOOL, buildJsonObject { put("query", "   ") }, "не должен быть пустым")
            assertControlledError(SEARCH_TOOL, buildJsonObject { put("query", 42) }, "должен быть строкой")
            assertControlledError(SEARCH_TOOL, buildJsonObject {
                put("query", "MCP")
                put("unexpected", true)
            }, "неизвестные поля")
            assertControlledError(SUMMARIZE_TOOL, buildJsonObject { put("matches", "wrong") }, "должен быть массивом")
            assertControlledError(SUMMARIZE_TOOL, buildJsonObject {
                putJsonArray("matches") {
                    add(buildJsonObject {
                        put("id", "source-1")
                        put("title", "Title")
                        put("content", "Content")
                        put("unexpected", true)
                    })
                }
            }, "неизвестные поля")
            assertControlledError(SAVE_TO_FILE_TOOL, buildJsonObject {
                put("fileName", "../outside.md")
                put("content", "do not write")
            }, "без пути")
            assertControlledError(SAVE_TO_FILE_TOOL, buildJsonObject {
                put("fileName", directory.resolve("absolute.md").toAbsolutePath().toString())
                put("content", "do not write")
            }, "без пути")
            assertControlledError(SAVE_TO_FILE_TOOL, buildJsonObject {
                put("fileName", "wrong.md")
                put("content", 123)
            }, "должен быть строкой")
            assertFalse(Files.exists(directory.resolve("outside.md")))
            assertEquals(emptyList(), Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString() == "absolute.md" || it.fileName.toString() == "outside.md" }.toList()
            })

            Files.createDirectories(outputDirectory)
            val outside = directory.resolve("symlink-target.md")
            Files.writeString(outside, "sentinel")
            Files.createSymbolicLink(outputDirectory.resolve("linked.md"), outside)
            assertControlledError(SAVE_TO_FILE_TOOL, buildJsonObject {
                put("fileName", "linked.md")
                put("content", "must not follow")
            }, "символьную ссылку")
            assertEquals("sentinel", Files.readString(outside))
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `existing tracker scheduler ping and echo tools continue to work`() = runBlocking {
        val directory = Files.createTempDirectory("mcp-existing-tools-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), directory.resolve("output"))
        try {
            assertEquals("pong", gateway.callTool("ping", buildJsonObject {}).content)
            assertEquals("unchanged", gateway.callTool("echo", buildJsonObject { put("text", "unchanged") }).content)
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
