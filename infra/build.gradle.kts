import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.encoding)
    implementation(libs.slf4j.api)
    implementation(project(":core"))

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.logback.classic)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // the JUnit platform never sees a kotest spec tag, so the tag filter is kotest's own
    systemProperty("kotest.tags.exclude", "integration")
    // last-resort backstop: a test blocked on its own thread is not interrupted by kotest's own
    // timeout unless it opts into blockingTest, so this kills the task instead of hanging CI
    timeout.set(Duration.ofMinutes(5))
}
