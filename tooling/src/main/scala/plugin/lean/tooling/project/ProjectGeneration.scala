package plugin.lean.tooling.project

import java.io.IOException
import java.nio.file.{Files, LinkOption, Path}
import java.util.List as JavaList
import java.util.function.Consumer
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class ProjectFileConflictException(val paths: JavaList[Path]) extends IOException(
  "Project creation would replace existing files:\n" + paths.asScala.mkString("\n")
)

final class ProjectRecoveryException(val recoveryDirectory: Path, cause: Throwable) extends IOException(
  s"Project creation could not finish. Generated files remain in '$recoveryDirectory' and the destination directory.",
  cause
)

/**
 * Generates files in a sibling directory, then publishes them without replacing existing entries.
 * Generation failure leaves the destination unchanged. Publication failure moves published entries
 * back before cleanup; if that recovery fails, its remaining output is retained and reported.
 * Callers must not edit the generated entries until this operation completes.
 */
object ProjectGeneration {
  def generate(destination: Path, generator: Consumer[Path]): Unit = {
    val requested = destination.toAbsolutePath.normalize()
    val target = if Files.exists(requested) then requested.toRealPath() else requested
    val parent = Option(target.getParent).getOrElse(throw new IOException("Choose a project directory below a parent."))
    if !Files.isDirectory(parent) then throw new IOException(s"Project parent directory does not exist: $parent")
    if Files.exists(target) && !Files.isDirectory(target) then {
      throw new IOException(s"Project location is not a directory: $target")
    }

    Using.resource(new StagingDirectory(Files.createTempDirectory(parent, ".lean-project-"))) { staging =>
      generator.accept(staging.path)
      if Thread.currentThread().isInterrupted then throw new InterruptedException("Project creation was cancelled.")
      val entries = Using.resource(Files.list(staging.path))(_.iterator().asScala.toVector)
      val conflicts = entries.map(entry => target.resolve(entry.getFileName))
        .filter(Files.exists(_, LinkOption.NOFOLLOW_LINKS))
      if conflicts.nonEmpty then throw new ProjectFileConflictException(JavaList.copyOf(conflicts.asJava))

      var createdTarget = false
      var published = Vector.empty[(Path, Path)]
      try {
        if !Files.exists(target) then {
          Files.createDirectory(target)
          createdTarget = true
        }
        entries.foreach { source =>
          if Thread.currentThread().isInterrupted then throw new InterruptedException("Project creation was cancelled.")
          val output = target.resolve(source.getFileName)
          Files.move(source, output)
          published = published :+ (source, output)
        }
      } catch {
        case error: Exception => {
          published.reverse.foreach { case (source, output) =>
            try Files.move(output, source)
            catch {
              case recovery: Exception => {
                staging.retain = true
                error.addSuppressed(recovery)
              }
            }
          }
          if createdTarget && !staging.retain then {
            try Files.delete(target)
            catch {
              case recovery: Exception => {
                staging.retain = true
                error.addSuppressed(recovery)
              }
            }
          }
          if staging.retain then throw new ProjectRecoveryException(staging.path, error)
          else throw error
        }
      }
    }
  }

  private final class StagingDirectory(val path: Path) extends AutoCloseable {
    var retain = false

    override def close(): Unit = {
      if !retain then {
        Using.resource(Files.walk(path)) { entries =>
          entries.iterator().asScala.toVector.reverse.foreach(Files.delete)
        }
      }
    }
  }
}
