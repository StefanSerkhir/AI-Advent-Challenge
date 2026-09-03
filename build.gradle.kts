plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
}

dependencies {
    val ktorVersion = "3.5.1"

    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.0")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "org.example.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "LLMWorkbench"
            packageVersion = "1.0.0"
            description = "Desktop client for comparing LLM response strategies"
            vendor = "AI Advent Challenge"

            macOS {
                iconFile.set(project.file("src/main/resources/icons/app-icon.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/app-icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/app-icon.png"))
            }
        }
    }
}
