package plugin.lean.tooling

import java.io.{File, IOException}
import java.nio.file.{Files, Path}
import java.util.{List as JavaList, Map as JavaMap, Optional}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** An immutable process specification. Arguments are passed directly, never through a shell. */
final class ToolCommand(
  val executable: Path,
  arguments: JavaList[String],
  val directory: Path,
  environment: JavaMap[String, String]
) {
  val argumentsList: JavaList[String] = JavaList.copyOf(arguments)
  val environmentMap: JavaMap[String, String] = JavaMap.copyOf(environment)

  def inDirectory(directory: Path): ToolCommand = {
    new ToolCommand(executable, argumentsList, directory, environmentMap)
  }
}

final class ElanNotFoundException(val searchedPaths: JavaList[Path]) extends IOException(
  "Cannot find elan. Set ELAN_HOME, add elan to PATH, or select its executable in Lean settings."
)

/** Owns executable discovery and command construction, but does not execute processes. */
final class ElanInstallation private (
  val executable: Path,
  environment: JavaMap[String, String],
  windows: Boolean
) {
  private val processEnvironment = JavaMap.copyOf(environment)

  def inspect(directory: Path, selection: Optional[String]): ToolCommand = {
    command(directory, List("dump-state", "--no-net"), selection)
  }

  def install(directory: Path, toolchain: String): ToolCommand = {
    validateToolchainName(toolchain)
    command(directory, List("toolchain", "install", "--", toolchain), Optional.empty())
  }

  def run(directory: Path, toolchain: String, arguments: JavaList[String]): ToolCommand = {
    validateToolchainName(toolchain)
    require(!arguments.isEmpty, "A tool command is required")
    command(directory, List("run", "--", toolchain) ++ arguments.asScala, Optional.empty())
  }

  private def command(directory: Path, arguments: List[String], selection: Optional[String]): ToolCommand = {
    val selectedEnvironment = selection.toScala match {
      case None => processEnvironment
      case Some(name) => {
        validateToolchainName(name)
        val inherited = processEnvironment.asScala.filterNot { case (key, _) =>
          if windows then key.equalsIgnoreCase("ELAN_TOOLCHAIN") else key == "ELAN_TOOLCHAIN"
        }.toMap
        (inherited + ("ELAN_TOOLCHAIN" -> name)).asJava
      }
    }
    new ToolCommand(executable, arguments.asJava, directory, selectedEnvironment)
  }

  private def validateToolchainName(name: String): Unit = {
    require(name.nonEmpty && !name.contains('\u0000'), "A nonempty elan toolchain name is required")
  }
}

object ElanInstallation {
  /**
   * An explicit executable is authoritative. Otherwise search ELAN_HOME/bin, PATH,
   * then elan's default home. ELAN_HOME is preserved for distro-managed elan binaries.
   * All environment and home values come from the hosting process boundary.
   */
  def discover(
    environment: JavaMap[String, String],
    userHome: Path,
    explicitExecutable: Optional[Path],
    windows: Boolean
  ): ElanInstallation = {
    val executableName = if windows then "elan.exe" else "elan"
    def variable(name: String): Option[String] = {
      environment.asScala.iterator.collectFirst {
        case (key, value) if key == name || (windows && key.equalsIgnoreCase(name)) => value
      }.filter(_.nonEmpty)
    }

    val candidates =
      if explicitExecutable.isPresent then List(explicitExecutable.get())
      else {
        val configuredHome = variable("ELAN_HOME").map(Path.of(_))
        val pathDirectories = variable("PATH").toList.flatMap { value =>
          value.split(if windows then ";" else File.pathSeparator, -1).toList.filter(_.nonEmpty).map(Path.of(_))
        }
        configuredHome.map(_.resolve("bin").resolve(executableName)).toList ++
        pathDirectories.map(_.resolve(executableName)) ++
        Option.when(configuredHome.isEmpty)(userHome.resolve(".elan").resolve("bin").resolve(executableName))
      }
    val normalized = candidates.map(_.toAbsolutePath.normalize()).distinct
    val executable = normalized.find { candidate =>
      Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))
    }.getOrElse(throw new ElanNotFoundException(JavaList.copyOf(normalized.asJava)))
    new ElanInstallation(executable, environment, windows)
  }
}
