import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
