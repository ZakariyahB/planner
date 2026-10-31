import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: Google Calendar access behind an interface, the approval gate and the only CalendarWriter.
// Depends on :engine. The assistant module must never depend on this one (that is how "the AI cannot write" is enforced).
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
