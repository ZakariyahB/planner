import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the scheduling engine, the domain model, the ChangeSet model and the ports every layer codes against.
// No Android dependencies, so it is tested on the JVM (see scripts/jvm-test.sh).
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
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
