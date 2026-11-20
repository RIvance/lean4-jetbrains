package plugin.lean.tooling

import com.google.gson.{JsonElement, JsonObject, JsonParseException, JsonParser}
import java.io.IOException
import java.nio.file.{InvalidPathException, Path}
import java.util.List as JavaList
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

final case class InstalledToolchain(name: String, path: Path)

enum ToolchainResolution {
  case Resolved(name: String)
  case Unresolved(reason: String, cached: Option[String])
}

final case class SelectedToolchain(toolchain: InstalledToolchain, source: ResolutionSource)
final case class ElanSelectionOrigin(kind: ElanSelectionKind, location: Optional[String])

final class ElanProtocolException(message: String, cause: Throwable = null) extends IOException(message, cause)

final class ToolchainUnavailableException(message: String, val installTarget: Optional[String] = Optional.empty())
  extends IOException(message)

/** Only remote requests have an elan download target. Paths may refer to builds outside elan's inventory. */
private enum ElanToolchainRequest {
  case Remote(identifier: String)
  case Directory(path: Path)
  case Other
}

/** A snapshot of elan's structured, offline inventory. Paths and identities are supplied by elan. */
final class ElanState private (
  val version: String,
  installed: Vector[InstalledToolchain],
  val active: Option[ToolchainResolution],
  request: Option[ElanToolchainRequest],
  val selectionOrigin: Optional[ElanSelectionOrigin]
) {
  val installedToolchains: JavaList[InstalledToolchain] = JavaList.copyOf(installed.asJava)

  /** Cached resolutions must already be installed. Only elan's own path variant can bypass the named inventory. */
  private[tooling] def selectedToolchain(validateDirectory: Path => Unit): SelectedToolchain = {
    val remote = request.collect { case ElanToolchainRequest.Remote(identifier) => identifier }
    active match {
      case Some(ToolchainResolution.Resolved(name)) =>
        val toolchain = installed.find(_.name == name).getOrElse {
          request match {
            case Some(ElanToolchainRequest.Directory(_)) =>
              val path = Path.of(name)
              if !path.isAbsolute then throw new ElanProtocolException("elan resolved a relative toolchain directory")
              validateDirectory(path)
              InstalledToolchain(name, path)
            case _ =>
              // Older dumps can supply an exact resolved name without describing the original request.
              val target = remote.orElse(Option.when(request.isEmpty)(name))
              throw new ToolchainUnavailableException(s"Lean toolchain '$name' is not installed.", target.toJava)
          }
        }
        SelectedToolchain(toolchain, ResolutionSource.LIVE)
      case Some(ToolchainResolution.Unresolved(reason, cached)) =>
        val toolchain = cached.flatMap(name => installed.find(_.name == name)).getOrElse {
          val message = request match {
            case Some(ElanToolchainRequest.Remote(id)) =>
              s"Elan could not resolve '$id' during offline inspection.\n$reason"
            case Some(ElanToolchainRequest.Directory(path)) => s"Elan could not resolve the directory '$path'.\n$reason"
            case _ => reason
          }
          throw new ToolchainUnavailableException(message, remote.toJava)
        }
        SelectedToolchain(toolchain, ResolutionSource.CACHE)
      case None =>
        val message = if installed.isEmpty then "No Lean toolchains are installed. Install one or select a local build."
        else "No Lean toolchain is selected. Choose a toolchain for this project or add a lean-toolchain file."
        throw new ToolchainUnavailableException(message)
    }
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
      val (request, origin) = selection(toolchains)
      new ElanState(version, installed, active, request, origin.toJava)
    } catch {
      case error: JsonParseException => throw new ElanProtocolException("elan returned invalid JSON", error)
      case error: InvalidPathException => {
        throw new ElanProtocolException("elan returned an invalid toolchain path", error)
      }
    }
  }

  /** Older dumps can omit provenance. A new override reason does not invalidate an otherwise usable inventory. */
  private def selection(toolchains: JsonObject): (Option[ElanToolchainRequest], Option[ElanSelectionOrigin]) = {
    Option(toolchains.get("active_override")) match {
      case Some(value) if value.isJsonObject =>
        val record = value.getAsJsonObject
        (toolchainRequest(record), Option(record.get("reason")).flatMap(overrideOrigin))
      case Some(value) if value.isJsonNull =>
        val record = Option(toolchains.get("default")).filter(_.isJsonObject)
        (record.flatMap(r => toolchainRequest(r.getAsJsonObject)),
          record.map(_ => ElanSelectionOrigin(ElanSelectionKind.DEFAULT, Optional.empty())))
      case _ => (None, None)
    }
  }

  private def toolchainRequest(record: JsonObject): Option[ElanToolchainRequest] = {
    Option(record.get("unresolved")).filterNot(_.isJsonNull).map { value =>
      if !value.isJsonObject then ElanToolchainRequest.Other
      else {
        val entry = value.getAsJsonObject
        if entry.has("Remote") then {
          val remote = objectValue(entry.get("Remote"), "Remote")
          val origin = stringValue(field(remote, "origin"), "origin")
          val release = stringValue(field(remote, "release"), "release")
          ElanToolchainRequest.Remote(s"$origin:$release")
        } else if entry.has("Path") then {
          val descriptor = objectValue(entry.get("Path"), "Path")
          val path = Path.of(stringValue(field(descriptor, "path"), "path"))
          if !path.isAbsolute then throw new ElanProtocolException("elan returned a relative toolchain directory")
          ElanToolchainRequest.Directory(path)
        } else ElanToolchainRequest.Other
      }
    }
  }

  private def overrideOrigin(reason: JsonElement): Option[ElanSelectionOrigin] = {
    if reason.isJsonPrimitive && reason.getAsString == "Environment" then {
      return Some(ElanSelectionOrigin(ElanSelectionKind.ENVIRONMENT, Optional.empty()))
    }
    if !reason.isJsonObject || reason.getAsJsonObject.size() != 1 then return None
    val entry = reason.getAsJsonObject.entrySet().iterator().next()
    val kind = entry.getKey match {
      case "ToolchainFile" | "LeanpkgFile" => ElanSelectionKind.CONFIGURATION_FILE
      case "OverrideDB" => ElanSelectionKind.DIRECTORY_OVERRIDE
      case "InToolchainDirectory" => ElanSelectionKind.TOOLCHAIN_DIRECTORY
      case _ => return None
    }
    val location = Option(entry.getValue).filter(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString)
      .map(_.getAsString).filterNot(_.isBlank)
    Some(ElanSelectionOrigin(kind, location.toJava))
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
