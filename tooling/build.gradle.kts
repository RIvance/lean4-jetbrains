import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.scala.ScalaCompile

plugins {
    `java-library`
    scala
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Keep Scala and its Java boundary types in one compilation unit and one class-output directory.
sourceSets.main {
    scala.srcDirs(java.srcDirs)
    java.setSrcDirs(emptyList<String>())
    val compiledDirectory = tasks.named<ScalaCompile>("compileScala").flatMap { it.destinationDirectory }
    (output.classesDirs as ConfigurableFileCollection).setFrom(compiledDirectory)
}

dependencies {
    api("org.scala-lang:scala3-library_3:3.3.7")
    // IntelliJ supplies Gson. A bundled copy would give LSP JSON values a different class identity.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
