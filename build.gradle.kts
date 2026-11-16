import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    application
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}

configure(listOf(project(":shared"), project(":frontend"), project(":backend"))) {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
}

allprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                apiVersion = KotlinVersion.KOTLIN_2_3
                languageVersion = KotlinVersion.KOTLIN_2_3
            }
        }
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation(project(":tooling"))
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledModule("intellij.platform.lsp")
        bundledPlugin("com.intellij.modules.jcef")

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))

        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261.26222"
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        ides {
            val versions = providers.gradleProperty("verificationIdes").map { it.split(',').map(String::trim) }
                .orElse(listOf(
                    "IU-2026.1.4", "IU-2026.2.4", "IU-2026.3",
                    "WS-2026.1.4", "WS-2026.3", "PY-2026.3", "PS-2026.3",
                    "RM-2026.3", "GO-2026.3", "RR-2026.3", "RD-2026.3",
                    "DB-2026.3", "CL-2026.3.0.1", "DS-2026.1.3.1"
                ))
            create(versions) { notation ->
                val (productCode, productVersion) = notation.split('-', limit = 2)
                val platform = IntelliJPlatformType.fromCode(productCode, productVersion)
                type = platform
                version = productVersion
                // Rider's SDK archive is required by the Gradle verifier integration.
                useInstaller = platform != IntelliJPlatformType.Rider
            }
        }
    }
}

