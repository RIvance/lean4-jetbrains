import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    plugins {
        id("rpc") version "2.4.0-RC-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
        id("com.github.node-gradle.node") version "7.1.0"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.20.0"
}

rootProject.name = "plugin.lean"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
        ivy {
            name = "nodeDistributions"
            url = uri("https://nodejs.org/dist")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }

    }
}

include("shared")
include("frontend")
include("backend")
include("tooling")
