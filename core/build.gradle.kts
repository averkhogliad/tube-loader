import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-test-fixtures`
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":common:config"))

    testFixturesApi(libs.kotest.runner.junit6)
    testFixturesApi(libs.kotest.assertions.core)

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project()))
    testImplementation(testFixtures(project(":common:config")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // last-resort backstop: a test blocked on its own thread is not interrupted by kotest's own
    // timeout unless it opts into blockingTest, so this kills the task instead of hanging CI
    timeout.set(Duration.ofMinutes(5))
}
