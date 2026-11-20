package plugin.lean.tooling

import com.google.gson.{JsonElement, JsonParseException, JsonParser}
import java.io.IOException
import java.util.List as JavaList
import scala.jdk.CollectionConverters.*

final case class LeanRelease(channel: LeanReleaseChannel, name: String) {
  def toolchain: String = channel.toolchain(name)
}

/** The official index orders each channel newest first. Elan records dated nightlies under the nightly repository. */
object LeanReleaseIndex {
  def parse(json: String): JavaList[LeanRelease] = {
    val root = try JsonParser.parseString(json) catch {
      case error: JsonParseException => throw new IOException("The Lean release index contains invalid JSON.", error)
    }
    if !root.isJsonObject || stringField(root, "version") != "1" then {
      throw new IOException("Unsupported Lean release index format.")
    }
    val releases = LeanReleaseChannel.values().toVector.flatMap { channel =>
      val entries = root.getAsJsonObject.get(channel.indexKey)
      if entries == null || !entries.isJsonArray then {
        throw new IOException(s"The Lean release index is missing the ${channel.indexKey} release list.")
      }
      entries.getAsJsonArray.asScala.map(entry => LeanRelease(channel, stringField(entry, "name")))
    }
    JavaList.copyOf(releases.asJava)
  }

  private def stringField(record: JsonElement, key: String): String = {
    val value = if record.isJsonObject then record.getAsJsonObject.get(key) else null
    if value == null || !value.isJsonPrimitive || !value.getAsJsonPrimitive.isString || value.getAsString.isBlank then {
      throw new IOException(s"The Lean release index has an invalid '$key' field.")
    }
    value.getAsString
  }
}
