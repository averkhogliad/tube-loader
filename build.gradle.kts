plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

group = "io.averkhogliad"
version = "0.0.1"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
            currentProject {
                sources { excludedSourceSets.add("testFixtures") }
            }
            reports {
                total {
                    verify {
                        rule("line") { minBound(80) }
                        rule("branch") { minBound(75, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
                    }
                }
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>("ktlint") {
            version.set(libs.versions.ktlintCli.get())
        }
    }
}
