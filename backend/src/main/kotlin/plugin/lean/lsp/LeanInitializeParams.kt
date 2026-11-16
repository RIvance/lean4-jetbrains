package plugin.lean.lsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler

/** Native initialization parameters with Lean's additional client capabilities on the wire. */
@JsonAdapter(LeanInitializeParamsAdapter::class)
class LeanInitializeParams : InitializeParams() {
    companion object {
        private val gson = MessageJsonHandler(emptyMap()).gson

        fun from(params: InitializeParams): LeanInitializeParams =
            gson.fromJson(gson.toJsonTree(params), LeanInitializeParams::class.java)
    }
}

private class LeanInitializeParamsAdapter : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
        val reader = gson.getDelegateAdapter(this, type)
        val json = gson.getAdapter(JsonElement::class.java)
        return object : TypeAdapter<T>() {
            override fun write(writer: JsonWriter, value: T) {
                // The native capabilities adapter drops subclass fields, so extend the complete parameters instead.
                val fields = gson.toJsonTree(value, InitializeParams::class.java).asJsonObject
                fields.getAsJsonObject("capabilities").add("lean", JsonObject().apply {
                    addProperty("incrementalDiagnosticSupport", true)
                    addProperty("silentDiagnosticSupport", true)
                    addProperty("rpcWireFormat", "v1")
                })
                // Preserve explicit nulls emitted by native adapters, including the required nullable rootUri.
                val serializeNulls = writer.serializeNulls
                try {
                    writer.serializeNulls = true
                    json.write(writer, fields)
                } finally {
                    writer.serializeNulls = serializeNulls
                }
            }

            override fun read(input: JsonReader): T = reader.read(input)
        }
    }
}
