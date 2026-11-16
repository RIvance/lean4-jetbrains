package plugin.lean.persistence

import java.nio.ByteBuffer
import java.util.Base64

/** Keeps literal strings opaque to the platform's XML path-macro processing. */
internal fun encodeLiteralText(value: String): String {
    val bytes = ByteBuffer.allocate(value.length * Char.SIZE_BYTES)
    value.forEach { bytes.putChar(it) }
    return Base64.getUrlEncoder().encodeToString(bytes.array())
}

internal fun decodeLiteralText(value: String): String {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size % Char.SIZE_BYTES == 0) { "Incomplete UTF-16 data." }
    return ByteBuffer.wrap(bytes).asCharBuffer().toString()
}
