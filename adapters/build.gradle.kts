import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core"))
    implementation(project(":infra"))
    implementation(project(":common:retry"))

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
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

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the live integration specs (real tools, opt-in)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("kotest.tags.include", "integration")
    // same backstop as `test`: a live tool may block a thread past kotest's own timeout
    timeout.set(Duration.ofMinutes(5))
}

kover {
    currentProject {
        instrumentation {
            // koverVerify waits for every instrumented Test task, so the live suite would land in `check`
            disabledForTestTasks.add("integrationTest")
        }
    }
}
