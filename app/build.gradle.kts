import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    api(project(":usecases"))
    api(project(":common:config"))

    implementation(project(":adapters"))
    implementation(project(":infra"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.encoding)

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // last-resort backstop: a test blocked on its own thread is not interrupted by kotest's own
    // timeout unless it opts into blockingTest, so this kills the task instead of hanging CI
    timeout.set(Duration.ofMinutes(5))
}
