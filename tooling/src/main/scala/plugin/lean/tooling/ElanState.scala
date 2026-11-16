package plugin.lean.tooling

import com.google.gson.{JsonElement, JsonObject, JsonParseException, JsonParser}
import java.io.IOException
import java.nio.file.{InvalidPathException, Path}
import java.util.List as JavaList
import scala.jdk.CollectionConverters.*

final case class InstalledToolchain(name: String, path: Path)

enum ToolchainResolution {
  case Resolved(name: String)
  case Unresolved(reason: String, cached: Option[String])
}

final case class SelectedToolchain(toolchain: InstalledToolchain, source: ResolutionSource)

final class ElanProtocolException(message: String, cause: Throwable = null) extends IOException(message, cause)

final class ToolchainUnavailableException(message: String) extends IOException(message)

/** A snapshot of elan's structured, offline inventory. Paths and identities are supplied by elan. */
final class ElanState private (
  val version: String,
  installed: Vector[InstalledToolchain],
  val active: Option[ToolchainResolution]
) {
  val installedToolchains: JavaList[InstalledToolchain] = JavaList.copyOf(installed.asJava)

  /** Cached channel resolutions may be used only if that exact toolchain is already installed. */
  def selectedToolchain(): SelectedToolchain = {
    val (name, source) = active match {
      case Some(ToolchainResolution.Resolved(name)) => (name, ResolutionSource.LIVE)
      case Some(ToolchainResolution.Unresolved(_, Some(cached))) => (cached, ResolutionSource.CACHE)
      case Some(ToolchainResolution.Unresolved(reason, None)) => throw new ToolchainUnavailableException(reason)
      case None => throw new ToolchainUnavailableException(
        "No Lean toolchain is selected. Add a lean-toolchain file or select a toolchain in Lean settings."
      )
    }
    val toolchain = installed.find(_.name == name).getOrElse(throw new ToolchainUnavailableException(
      s"Lean toolchain '$name' is not installed. Install it with elan or from Lean settings."
    ))
    SelectedToolchain(toolchain, source)
  }
}

object ElanState {
  def parse(json: String): ElanState = {
    try {
      val root = objectValue(JsonParser.parseString(json), "state")
      val version = stringValue(field(objectValue(field(root, "elan_version"), "elan_version"), "current"), "current")
      val toolchains = objectValue(field(root, "toolchains"), "toolchains")
      val installedJson = field(toolchains, "installed")
      if !installedJson.isJsonArray then throw new ElanProtocolException("elan: 'installed' must be an array")
      val installed = installedJson.getAsJsonArray.asScala.toVector.map { entry =>
        val record = objectValue(entry, "installed toolchain")
        val name = stringValue(field(record, "resolved_name"), "resolved_name")
        val path = Path.of(stringValue(field(record, "path"), "path"))
        if !path.isAbsolute then throw new ElanProtocolException("elan returned a relative toolchain path")
        InstalledToolchain(name, path.normalize())
      }
      if installed.map(_.name).distinct.size != installed.size then {
        throw new ElanProtocolException("elan returned duplicate toolchain identities")
      }
      val activeJson = field(toolchains, "resolved_active")
      val active = Option.unless(activeJson.isJsonNull) {
        val resolution = objectValue(activeJson, "resolved_active")
        val live = objectValue(field(resolution, "live"), "live")
        if live.has("Ok") && !live.has("Err") then {
          ToolchainResolution.Resolved(stringValue(live.get("Ok"), "Ok"))
        } else if live.has("Err") && !live.has("Ok") then {
          val cachedJson = field(resolution, "cached")
          val cached = Option.unless(cachedJson.isJsonNull)(stringValue(cachedJson, "cached"))
          ToolchainResolution.Unresolved(stringValue(live.get("Err"), "Err"), cached)
        } else {
          throw new ElanProtocolException("elan returned an invalid toolchain resolution")
        }
      }
      new ElanState(version, installed, active)
    } catch {
      case error: JsonParseException => throw new ElanProtocolException("elan returned invalid JSON", error)
      case error: InvalidPathException => {
        throw new ElanProtocolException("elan returned an invalid toolchain path", error)
      }
    }
  }

  private def field(value: JsonObject, name: String): JsonElement = {
    Option(value.get(name)).getOrElse(throw new ElanProtocolException(s"elan state is missing '$name'"))
  }

  private def objectValue(value: JsonElement, name: String): JsonObject = {
    if !value.isJsonObject then throw new ElanProtocolException(s"elan: '$name' must be an object")
    value.getAsJsonObject
  }

  private def stringValue(value: JsonElement, name: String): String = {
    if !value.isJsonPrimitive || !value.getAsJsonPrimitive.isString || value.getAsString.isEmpty then {
      throw new ElanProtocolException(s"elan: '$name' must be a nonempty string")
    }
    value.getAsString
  }
}
