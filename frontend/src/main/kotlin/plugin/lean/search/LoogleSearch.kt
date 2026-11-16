package plugin.lean.search

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.util.net.PlatformHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8

data class LoogleHit(val name: String, val module: String, val type: String, val documentation: String?) {
    val documentationUri: URI
        get() = URI(
            "https", "leanprover-community.github.io",
            "/mathlib4_docs/${module.replace('.', '/')}.html", null, name
        )
}

sealed interface LoogleResponse {
    data class Matches(val count: Int, val header: String, val hits: List<LoogleHit>) : LoogleResponse
    data class Problem(val message: String, val suggestions: List<String>) : LoogleResponse

    companion object {
        /** A malformed response is a transport failure, never an empty successful search. */
        fun parse(text: String): LoogleResponse = try {
            val root = objectValue(JsonParser.parseString(text))
            if (root.has("error")) {
                val suggestions = root["suggestions"]?.let { array(it).map(::string) }.orEmpty()
                Problem(string(root["error"]), java.util.List.copyOf(suggestions))
            } else {
                val countValue = root["count"]
                require(countValue?.isJsonPrimitive == true && countValue.asJsonPrimitive.isNumber) {
                    "Expected a numeric match count"
                }
                val count = countValue.asBigDecimal.intValueExact()
                val hits = array(root["hits"]).map { entry ->
                    val hit = objectValue(entry)
                    LoogleHit(
                        string(hit["name"]), string(hit["module"]), string(hit["type"]),
                        hit["doc"]?.takeUnless { it.isJsonNull }?.let(::string)
                    )
                }
                require(count >= hits.size) { "The match count is smaller than the returned result set" }
                Matches(count, string(root["header"]), java.util.List.copyOf(hits))
            }
        } catch (error: JsonParseException) {
            throw IOException("Loogle returned an invalid search response.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Loogle returned an invalid search response.", error)
        } catch (error: ArithmeticException) {
            throw IOException("Loogle returned an invalid search response.", error)
        }

        private fun objectValue(value: JsonElement?): JsonObject {
            require(value?.isJsonObject == true) { "Expected an object" }
            return value.asJsonObject
        }

        private fun array(value: JsonElement?): JsonArray {
            require(value?.isJsonArray == true) { "Expected an array" }
            return value.asJsonArray
        }

        private fun string(value: JsonElement?): String {
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Expected a string" }
            return value.asString
        }
    }
}

/** Only an explicitly submitted query is sent to the online service; cancellation aborts the request. */
fun interface LoogleSearch {
    suspend fun search(query: String): LoogleResponse
}

@Service(Service.Level.APP)
class LoogleClient : LoogleSearch, Disposable {
    private val client = PlatformHttpClient.client()

    override suspend fun search(query: String): LoogleResponse = runInterruptible(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a Loogle query." }
        val request = PlatformHttpClient.requestBuilder(queryUri(query))
            .header("Accept", "application/json")
            .header("X-Loogle-Client", "lean4-jetbrains")
            .build()
        val response = PlatformHttpClient.send(client, request, HttpResponse.BodyHandlers.ofString(UTF_8))
        LoogleResponse.parse(response)
    }

    override fun dispose() = client.shutdownNow()

    companion object {
        fun queryUri(query: String): URI =
            URI.create("https://loogle.lean-lang.org/json?q=${URLEncoder.encode(query, UTF_8)}")
    }
}
