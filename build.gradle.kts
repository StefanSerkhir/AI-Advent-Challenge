plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"
repositories { mavenCentral() }

dependencies {
    val ktorVersion = "3.5.1"
    val mcpVersion = "0.15.0"
    val pdfBoxVersion = "3.0.8"
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")
    implementation("org.apache.pdfbox:pdfbox:$pdfBoxVersion")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
application { mainClass = "org.example.web.WebMainKt" }

val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val npmExecutable = System.getenv("PATH").split(File.pathSeparator)
    .map { file("$it/$npmCommand") }.firstOrNull { it.isFile }?.absolutePath ?: npmCommand
val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir("frontend")
    commandLine(npmExecutable, "ci", "--no-audit", "--no-fund")
    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
}
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir("frontend")
    commandLine(npmExecutable, "run", "build")
    inputs.files(fileTree("frontend") {
        exclude("node_modules/**", "dist/**", "test-results/**", "playwright-report/**")
    })
    outputs.dir("frontend/dist")
}
tasks.processResources {
    dependsOn(frontendBuild)
    from("frontend/dist") { into("web") }
}
tasks.withType<JavaExec>().configureEach {
    if (project.hasProperty("webPort")) environment("WEB_PORT", project.property("webPort").toString())
    systemProperty("java.awt.headless", "true")
}
tasks.register<JavaExec>("runWeb") {
    group = "application"
    description = "Build the frontend and start the local Ktor application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
}
tasks.register<JavaExec>("runMcpDemo") {
    group = "application"
    description = "Start the local MCP stdio server and verify discovery, pipeline, Tracker and scheduler tools"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.mcp.McpDemoClientKt"
}
tasks.register<JavaExec>("runLocalLlmDemo") {
    group = "verification"
    description = "Run three real streaming prompts against local Ollama qwen3:14b on 127.0.0.1:11434"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.cli.LocalLlmDemoKt"
}
tasks.register<JavaExec>("buildDocumentIndexes") {
    group = "application"
    description = "Build local fixed and structure-aware document indexes with OpenAI or Ollama embeddings"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.indexing.IndexingCliKt"
}
tasks.register<JavaExec>("buildLocalDocumentIndex") {
    group = "application"
    description = "Build the structure-aware document index with local Ollama embeddings"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.indexing.IndexingCliKt"
    args(
        "--root", ".",
        "--output", ".llm-document-index",
        "--strategy", "structured",
        "--embedding-provider", "ollama",
        "--embedding-model", "qwen3-embedding:0.6b",
        "--batch-size", "32",
    )
}
tasks.register<JavaExec>("runRagEvaluation") {
    group = "application"
    description = "Run local Ollama RAG evaluation and stability repeats; cloud comparison requires explicit --allow-cloud"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.rag.RagEvaluationCliKt"
}
tasks.register<JavaExec>("runLocalLlmOptimization") {
    group = "verification"
    description = "Run the real-local Qwen3 RAG parameter search and final repeated A/B evaluation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.optimization.LocalLlmOptimizationCliKt"
}
// Test-only entry point: deterministic clients are never packaged in the application.
tasks.register<JavaExec>("runWebFixture") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.example.web.WebFixtureKt"
}
tasks.register<JavaExec>("runDocumentIndexFixture") {
    group = "verification"
    description = "Build and reload both document indexes with deterministic fake embeddings and no network"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.example.indexing.IndexingFixtureKt"
}
tasks.register<JavaExec>("runRagEvaluationFixture") {
    group = "verification"
    description = "Create a 10-case RAG JSON/Markdown report with deterministic fakes and no network"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.example.rag.RagEvaluationFixtureKt"
}
