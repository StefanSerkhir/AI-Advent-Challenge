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
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
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
// Test-only entry point: deterministic clients are never packaged in the application.
tasks.register<JavaExec>("runWebFixture") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.example.web.WebFixtureKt"
}
tasks.register<JavaExec>("contextBenchmark") {
    group = "verification"
    description = "Compare full-history and summary-plus-recent context over one deterministic dialogue"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.example.benchmark.ContextCompressionBenchmarkKt"
}
