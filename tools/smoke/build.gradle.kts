import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.encoding)
    implementation(project(":core"))
    implementation(project(":common:config"))

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.averkhogliad.tubeloader.tools.smoke.RutubeSmokeKt")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=false")
}
