import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the in-app Claude (tools, prompt, loop). It proposes ChangeSets through ProposalPort and nothing else.
// Depends on :engine only. Do not add :calendar here: AssistantIsolationTest fails if CalendarWriter is reachable.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(project(":engine"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
