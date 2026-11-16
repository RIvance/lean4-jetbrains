
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    compileOnly(project(":tooling"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
    }
}
