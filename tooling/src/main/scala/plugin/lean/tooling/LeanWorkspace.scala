package plugin.lean.tooling

import java.nio.file.{Files, Path}
import java.util.List as JavaList
import scala.jdk.CollectionConverters.*

/** One Lean server scope: the nearest Lake project/toolchain boundary, or a standalone directory. */
final case class LeanWorkspace(root: Path, usesLake: Boolean) {
  def serverArguments(options: JavaList[String]): JavaList[String] = {
    val prefix =
      if usesLake then List("lake", "serve", "--", root.toString)
      else List("lean", "--server", root.toString)
    JavaList.copyOf((prefix ++ options.asScala).asJava)
  }
}

object LeanWorkspace {
  def discover(file: Path, projectDirectory: Path): LeanWorkspace = {
    discoverDirectory(file.toAbsolutePath.normalize().getParent, projectDirectory)
  }

  def discoverDirectory(directory: Path, projectDirectory: Path): LeanWorkspace = {
    val start = directory.toAbsolutePath.normalize()
    val fallback = projectDirectory.toAbsolutePath.normalize()
    val root = Iterator.iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(path => hasLakefile(path) || Files.isRegularFile(path.resolve("lean-toolchain")))
      .getOrElse(if start.startsWith(fallback) then fallback else start)
    LeanWorkspace(root, hasLakefile(root))
  }

  private def hasLakefile(directory: Path): Boolean = {
    Files.isRegularFile(directory.resolve("lakefile.lean")) || Files.isRegularFile(directory.resolve("lakefile.toml"))
  }
}
