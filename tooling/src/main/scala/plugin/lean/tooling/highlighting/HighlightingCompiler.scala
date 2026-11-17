package plugin.lean.tooling.highlighting

import com.google.gson.{JsonParseException, JsonParser}
import plugin.lean.tooling.{InstalledToolchain, ToolCommand}

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.util.{HexFormat, List as JavaList, Map as JavaMap, Optional}
import java.util.function.BiFunction
import java.util.zip.ZipInputStream
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Builds only bundled sources, with the selected installation's tools, outside the user's project. */
final class HighlightingCompiler(
  cacheRoot: Path,
  archive: Array[Byte],
  execute: BiFunction[ToolCommand, Integer, String],
  checkCancelled: Runnable,
  windows: Boolean
) {
  private val sourceDigest = MessageDigest.getInstance("SHA-256").digest(archive)
  private val sources = {
    val zip = new ZipInputStream(new ByteArrayInputStream(archive))
    try {
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).filterNot(_.isDirectory).map { entry =>
        val path = Path.of(entry.getName).normalize()
        require(!path.isAbsolute && !path.startsWith(".."), "Invalid bundled highlighting source path")
        path -> zip.readAllBytes()
      }.toMap
    } finally zip.close()
  }
  private val releases = new String(sources(Path.of("supported-versions.txt")), UTF_8).linesIterator.toSet

  /** Unsupported releases, including prereleases, have no build plan. Linked toolchains are inspected too. */
  def prepare(toolchain: InstalledToolchain, environment: JavaMap[String, String]): Optional[HighlightingBuild] = {
    checkCancelled.run()
    Files.createDirectories(cacheRoot)
    val suffix = if windows then ".exe" else ""
    val lean = toolchain.path.resolve(s"bin/lean$suffix").toRealPath()
    val lake = toolchain.path.resolve(s"bin/lake$suffix").toRealPath()
    val isolated = environment.asScala.filterNot { case (name, _) =>
      HighlightingCompiler.excludedEnvironment.contains(name.toUpperCase(java.util.Locale.ROOT))
    }.toMap.asJava
    val probe = Files.createTempFile(cacheRoot, "Probe", ".lean")
    val report = try {
      Files.write(probe, sources(Path.of("Probe.lean")))
      execute.apply(new ToolCommand(lean, JavaList.of("--run", probe.toString), cacheRoot, isolated), 15_000)
    } finally Files.deleteIfExists(probe)
    val (version, commit) = HighlightingCompiler.readIdentity(report)
    Option.when(releases.contains(version)) {
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(sourceDigest)
      for (value <- List(version, commit, lean.toString, lake.toString)) {
        val bytes = value.getBytes(UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
        digest.update(bytes)
      }
      // Replacing an installation in place must not reuse an older native build.
      for (path <- List(lean, lake)) {
        digest.update(ByteBuffer.allocate(16)
          .putLong(Files.size(path)).putLong(Files.getLastModifiedTime(path).toMillis).array())
      }
      val identity = HexFormat.of().formatHex(digest.digest())
      new HighlightingBuild(
        identity, cacheRoot.resolve(identity), lean, lake, isolated, sources, execute, checkCancelled
      )
    }.toJava
  }
}

object HighlightingCompiler {
  private val excludedEnvironment = Set(
    "LEAN_PATH", "LEAN_SRC_PATH", "LEAN_SYSROOT", "LAKE_HOME", "LEAN", "LAKE", "ELAN_TOOLCHAIN",
    "LEAN_AR", "LEAN_CC", "LEAN_GITHASH", "LAKE_OVERRIDE_LEAN", "LAKE_CACHE_DIR",
    "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH"
  )

  private def readIdentity(report: String): (String, String) = {
    try {
      val json = JsonParser.parseString(report)
      if !json.isJsonObject then throw new IOException("Lean returned an invalid highlighting toolchain identity")
      def field(name: String): String = {
        val value = json.getAsJsonObject.get(name)
        if value == null || !value.isJsonPrimitive || !value.getAsJsonPrimitive.isString then {
          throw new IOException(s"Lean's highlighting toolchain identity is missing '$name'")
        }
        value.getAsString
      }
      (field("version"), field("githash"))
    } catch {
      case error: JsonParseException => throw new IOException("Lean returned an invalid toolchain identity", error)
    }
  }
}

/**
 * One source/toolchain identity owns one cache directory. An OS lock serializes writers across projects and IDEs;
 * only a successfully built and load-tested library is published. Process execution remains with the host.
 */
final class HighlightingBuild private[highlighting] (
  val identity: String,
  val directory: Path,
  lean: Path,
  lake: Path,
  environment: JavaMap[String, String],
  sources: Map[Path, Array[Byte]],
  execute: BiFunction[ToolCommand, Integer, String],
  checkCancelled: Runnable
) {
  def library(): Path = {
    Files.createDirectories(directory)
    val channel = FileChannel.open(directory.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      val lock = acquire(channel)
      try {
        val ready = directory.resolve("library.path")
        val cached = Option.when(Files.isRegularFile(ready))(Path.of(Files.readString(ready)))
          .filter(path => path.startsWith(directory) && Files.isRegularFile(path))
        val library = cached.getOrElse {
          sources.foreach { case (name, bytes) =>
            val target = directory.resolve(name)
            Files.createDirectories(target.getParent)
            Files.write(target, bytes)
          }
          run(lake, List("build", "SemanticHighlighting:shared"), 180_000)
          val output = run(lake, List("run", "plugin-path"), 15_000)
          val path = try {
            val json = JsonParser.parseString(output)
            if !json.isJsonPrimitive || !json.getAsJsonPrimitive.isString then {
              throw new IOException("Lake returned an invalid highlighting library path")
            }
            Path.of(json.getAsString).toAbsolutePath.normalize()
          } catch {
            case error: JsonParseException => throw new IOException("Lake returned an invalid library path", error)
          }
          if !path.startsWith(directory) || !Files.isRegularFile(path) then {
            throw new IOException("Lake did not produce a highlighting library in its build cache")
          }
          path
        }
        // Also validate cached binaries: missing dependencies or a damaged library must not prevent LSP startup.
        run(lean, List(s"--plugin=$library", "--run", directory.resolve("Probe.lean").toString), 15_000)
        Files.writeString(ready, library.toString)
        library
      } finally lock.release()
    } finally channel.close()
  }

  private def run(executable: Path, arguments: List[String], timeout: Int): String = {
    checkCancelled.run()
    execute.apply(new ToolCommand(executable, arguments.asJava, directory, environment), timeout)
  }

  private def acquire(channel: FileChannel): FileLock = {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
    while (true) {
      checkCancelled.run()
      val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
      if lock.nonEmpty then return lock.get
      if System.nanoTime() >= deadline then throw new IOException("Timed out waiting for the highlighting build")
      Thread.sleep(50)
    }
    throw new IllegalStateException("Unreachable lock acquisition")
  }
}
