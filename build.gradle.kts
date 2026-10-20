import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

// Single source of truth for the local API pin, shared with test.yml's comment.
val bossPluginApiVersion = rootProject.file(".boss-plugin-api-version").readText().trim()

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Local development reads the sibling repo's build output; CI reads the jar
    // the release / test workflow downloads to build/downloaded-deps.
    val bossPluginApiJar = if (useLocalDependencies) {
        files("$bossPluginApiPath/build/libs/boss-plugin-api-$bossPluginApiVersion.jar")
    } else {
        files("build/downloaded-deps/boss-plugin-api.jar")
    }
    compileOnly(bossPluginApiJar)
    // compileOnly does not reach the test classpath, and SchedMcpToolsTest builds
    // real McpToolDefinition objects. buildPluginJar takes only sourceSets.main
    // output plus resources, so this cannot leak into the shipped artifact.
    testImplementation(bossPluginApiJar)

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Host-provided at runtime (not bundled by buildPluginJar). Only the
    // JsonElement tree API is used, so no serialization compiler plugin.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-scheduler-$version.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Scheduler Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.scheduler.SchedDynamicPlugin",
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// The default :jar task writes the same basename into build/libs and would
// clobber buildPluginJar's output depending on task order. A classifier keeps
// the two apart.
tasks.jar {
    archiveClassifier.set("thin")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth).
tasks.processResources {
    // Declared as an input so a version bump re-runs the filter instead of
    // leaving the task UP-TO-DATE with a stale plugin.json.
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

tasks.build {
    dependsOn("buildPluginJar")
}
