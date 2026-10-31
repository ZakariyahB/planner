#!/usr/bin/env bash
# Runs the pure-Kotlin modules' tests without the Android SDK.
#
# The real build declares Android plugins at the root, so it cannot configure on a machine without the SDK.
# This script writes a throwaway Gradle build (outside the repo) whose subprojects point at the repo's
# source folders, with the same dependency graph as android/settings.gradle.kts, and runs `gradle test`.
#
# Usage: android/scripts/jvm-test.sh [module ...]     modules: core engine calendar assistant (default: all)
#        SCRATCH=/some/dir android/scripts/jvm-test.sh  to choose where the scratch build lives
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID="$REPO/android"
SCRATCH="${SCRATCH:-${TMPDIR:-/tmp}/planner-jvm-test-$(echo "$REPO" | md5sum | cut -c1-8)}"
MIRROR="https://maven-central.storage-download.googleapis.com/maven2/"

mkdir -p "$SCRATCH"
cat > "$SCRATCH/settings.gradle.kts" <<EOF
rootProject.name = "planner-jvm-test"
pluginManagement { repositories { maven("$MIRROR"); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven("$MIRROR"); mavenCentral() } }
include(":core", ":engine", ":calendar", ":assistant")
EOF

cat > "$SCRATCH/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}
EOF

# name  source-root  project-deps
module() {
    local name="$1" src="$2" deps="$3"
    mkdir -p "$SCRATCH/$name"
    {
        echo 'import org.jetbrains.kotlin.gradle.dsl.JvmTarget'
        echo 'plugins { kotlin("jvm"); kotlin("plugin.serialization") }'
        echo "sourceSets { main { kotlin.srcDir(\"$src/main/kotlin\"); resources.srcDir(\"$src/main/resources\") }; test { kotlin.srcDir(\"$src/test/kotlin\"); resources.srcDir(\"$src/test/resources\") } }"
        echo 'kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }'
        echo 'java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }'
        echo 'dependencies {'
        echo '    "api"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")'
        echo '    "api"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")'
        echo '    "testImplementation"(kotlin("test"))'
        echo '    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")'
        for d in $deps; do echo "    \"api\"(project(\":$d\"))"; done
        echo '}'
        echo "tasks.test { useJUnitPlatform(); workingDir = file(\"$src/..\"); testLogging { events(\"failed\"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }"
    } > "$SCRATCH/$name/build.gradle.kts"
}

# Keep in step with the real build files (android/*/build.gradle.kts).
module core      "$ANDROID/core/scheduler/src" ""
module engine    "$ANDROID/engine/src"         ""
module calendar  "$ANDROID/calendar/src"       "engine"
module assistant "$ANDROID/assistant/src"      "engine"

targets=()
for m in "${@:-core engine calendar assistant}"; do
    for one in $m; do targets+=(":$one:test"); done
done

cd "$SCRATCH"
exec gradle --console=plain -q "${targets[@]}"
