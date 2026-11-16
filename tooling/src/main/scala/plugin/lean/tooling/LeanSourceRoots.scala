package plugin.lean.tooling

import com.google.gson.{JsonParseException, JsonParser}
import java.io.IOException
import java.nio.file.{InvalidPathException, Path}
import java.util.List as JavaList
import scala.jdk.CollectionConverters.*

/** Source search paths reported by Lean in the selected workspace environment, in search order. */
final class LeanSourceRoots private (val paths: JavaList[Path])

object LeanSourceRoots {
  def parse(json: String, directory: Path): LeanSourceRoots = {
    try {
      val value = JsonParser.parseString(json)
      if !value.isJsonArray then throw new IOException("Lean source paths must be a JSON array")
      val paths = value.getAsJsonArray.asScala.map { entry =>
        if !entry.isJsonPrimitive || !entry.getAsJsonPrimitive.isString then {
          throw new IOException("Each Lean source path must be a string")
        }
        directory.resolve(entry.getAsString).toAbsolutePath.normalize()
      }.toList.distinct
      new LeanSourceRoots(JavaList.copyOf(paths.asJava))
    } catch {
      case error: JsonParseException => throw new IOException("Invalid Lean source path response", error)
      case error: InvalidPathException => throw new IOException("Invalid Lean source path", error)
    }
  }
}
