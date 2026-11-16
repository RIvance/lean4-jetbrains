import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.task.NodeTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("com.github.node-gradle.node")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":tooling"))
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
        bundledModule("intellij.platform.frontend")
        bundledPlugin("com.intellij.modules.jcef")

        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }

    implementation(project(":shared"))
    compileOnly(project(":tooling"))
}

val infoviewDirectory = rootProject.layout.projectDirectory.dir("infoview")

node {
    version = "22.14.0"
    download = true
    distBaseUrl = null
    nodeProjectDir = infoviewDirectory
}

val installInfoviewDependencies = tasks.register<NpmTask>("installInfoviewDependencies") {
    npmCommand = listOf("ci", "--ignore-scripts")
    inputs.files(
        infoviewDirectory.file("package.json"),
        infoviewDirectory.file("package-lock.json"),
        infoviewDirectory.file("renderer/package.json")
    )
    outputs.dir(infoviewDirectory.dir("node_modules"))
    outputs.dir(infoviewDirectory.dir("renderer/node_modules"))
}

val buildInfoview = tasks.register<NodeTask>("buildInfoview") {
    dependsOn(installInfoviewDependencies)
    workingDir = infoviewDirectory
    script = infoviewDirectory.file("build.mjs")
    inputs.files(
        infoviewDirectory.file("package.json"),
        infoviewDirectory.file("package-lock.json"),
        infoviewDirectory.file("build.mjs"),
        infoviewDirectory.file("tsconfig.json"),
        infoviewDirectory.file("renderer/package.json"),
        infoviewDirectory.file("renderer/tsconfig.json"),
        infoviewDirectory.file("renderer/rollup.config.mjs"),
        infoviewDirectory.file("renderer/LICENSE")
    )
    inputs.dir(infoviewDirectory.dir("renderer/src"))
    inputs.dir(infoviewDirectory.dir("src"))
    inputs.dir(infoviewDirectory.dir("public"))
    inputs.dir(infoviewDirectory.dir("licenses"))
    outputs.dir(infoviewDirectory.dir("dist"))
    outputs.dir(infoviewDirectory.dir("renderer/dist"))
}

tasks.processResources {
    dependsOn(buildInfoview)
    from(infoviewDirectory.dir("dist")) { into("lean/infoview") }
}

tasks.clean {
    delete(infoviewDirectory.dir("dist"))
    delete(infoviewDirectory.dir("renderer/dist"))
}
