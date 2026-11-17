import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val highlightingSources = tasks.register<Zip>("highlightingSources") {
    from(rootProject.layout.projectDirectory.dir("semantic-highlighting")) {
        include("**/*.lean", "supported-versions.txt", "LICENSE")
        exclude(".lake/**")
    }
    archiveFileName = "semantic-highlighting.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/highlighting")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(highlightingSources) { into("lean") }
}

dependencies {
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":tooling"))
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.lsp")
    }

    implementation(project(":shared"))
    compileOnly(project(":tooling"))
}
