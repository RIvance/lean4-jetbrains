package plugin.lean.tooling.highlighting

import com.google.gson.{JsonElement, JsonParser}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional
import scala.jdk.OptionConverters.*

final case class HighlightingFailure(code: Int, message: String)

/** Per-process protocol state. All callbacks are serialized here; cancellation and shutdown are not failures. */
final class HighlightingFailureDetector {
  private var requests = Set.empty[JsonElement]
  private var closing = false

  def sent(bytes: Array[Byte]): Unit = synchronized {
    val message = JsonParser.parseString(new String(bytes, UTF_8)).getAsJsonObject
    Option(message.get("method")).map(_.getAsString).foreach {
      case "shutdown" | "exit" => {
        closing = true
        requests = Set.empty
      }
      case "$/cancelRequest" => requests -= message.getAsJsonObject("params").get("id")
      case "textDocument/semanticTokens/full" | "textDocument/semanticTokens/range" => {
        if !closing then requests += message.get("id")
      }
      case _ => ()
    }
  }

  def received(bytes: Array[Byte]): Optional[HighlightingFailure] = synchronized {
    val message = JsonParser.parseString(new String(bytes, UTF_8)).getAsJsonObject
    // Server requests have their own ID space; a refresh request must not consume a pending client request.
    if message.has("method") then return Optional.empty()
    val id = message.get("id")
    val expected = requests.contains(id)
    requests -= id
    val failure = if closing || !message.has("error") then None else {
      val error = message.getAsJsonObject("error")
      val code = error.get("code").getAsInt
      code match {
        case -32800 | -32801 | -32802 => None // Cancellation and document changes are normal while editing.
        // Lean reports worker startup failures and crashes on any outstanding document request.
        case -32901 | -32902 => Some(HighlightingFailure(code, error.get("message").getAsString))
        case _ if expected => Some(HighlightingFailure(code, error.get("message").getAsString))
        case _ => None
      }
    }
    failure.toJava
  }
}
