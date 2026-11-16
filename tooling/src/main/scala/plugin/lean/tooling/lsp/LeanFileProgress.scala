package plugin.lean.tooling.lsp

import com.google.gson.{JsonElement, JsonObject}
import java.io.IOException
import java.util.{List as JavaList, OptionalInt}
import scala.jdk.CollectionConverters.*

final case class LspPosition(line: Int, character: Int) {
  require(line >= 0 && character >= 0, "LSP positions must be nonnegative")

  def isBefore(other: LspPosition): Boolean = {
    line < other.line || (line == other.line && character < other.character)
  }
}

final case class LeanProcessingRange(start: LspPosition, end: LspPosition, kind: LeanProcessingKind) {
  require(!end.isBefore(start), "A processing range must end at or after its start")
}

/** A complete replacement for one document's processing ranges, optionally tied to its LSP version. */
final class LeanFileProgress private (
  val uri: String,
  val version: OptionalInt,
  val ranges: JavaList[LeanProcessingRange]
)

object LeanFileProgress {
  def parse(value: JsonElement): LeanFileProgress = {
    val params = objectValue(value, "file progress")
    val document = objectValue(params.get("textDocument"), "textDocument")
    val uri = document.get("uri")
    if uri == null || !uri.isJsonPrimitive || !uri.getAsJsonPrimitive.isString || uri.getAsString.isEmpty then {
      throw new IOException("File progress requires a document URI")
    }
    val versionValue = document.get("version")
    val version = if versionValue == null || versionValue.isJsonNull then OptionalInt.empty()
    else OptionalInt.of(integer(versionValue, "version"))
    val processing = params.get("processing")
    if processing == null || !processing.isJsonArray then {
      throw new IOException("File progress requires an array of processing ranges")
    }
    val ranges = processing.getAsJsonArray.asScala.map { value =>
      val entry = objectValue(value, "processing entry")
      val range = objectValue(entry.get("range"), "range")
      val start = position(range.get("start"))
      val end = position(range.get("end"))
      if end.isBefore(start) then throw new IOException("A processing range ends before its start")
      val kind = if !entry.has("kind") then LeanProcessingKind.PROCESSING else {
        integer(entry.get("kind"), "kind") match {
          case 1 => LeanProcessingKind.PROCESSING
          case 2 => LeanProcessingKind.FATAL_ERROR
          case value => throw new IOException(s"Unknown Lean processing kind: $value")
        }
      }
      LeanProcessingRange(start, end, kind)
    }.toList
    new LeanFileProgress(uri.getAsString, version, JavaList.copyOf(ranges.asJava))
  }

  private def position(value: JsonElement): LspPosition = {
    val fields = objectValue(value, "position")
    val line = integer(fields.get("line"), "line")
    val character = integer(fields.get("character"), "character")
    if line < 0 || character < 0 then throw new IOException("LSP positions must be nonnegative")
    LspPosition(line, character)
  }

  private def objectValue(value: JsonElement, name: String): JsonObject = {
    if value == null || !value.isJsonObject then throw new IOException(s"Expected an object for $name")
    value.getAsJsonObject
  }

  private def integer(value: JsonElement, name: String): Int = {
    if value == null || !value.isJsonPrimitive || !value.getAsJsonPrimitive.isNumber then {
      throw new IOException(s"Expected an integer for $name")
    }
    try {
      value.getAsBigDecimal.intValueExact()
    } catch {
      case error: ArithmeticException => throw new IOException(s"Invalid integer for $name", error)
      case error: NumberFormatException => throw new IOException(s"Invalid integer for $name", error)
    }
  }
}
