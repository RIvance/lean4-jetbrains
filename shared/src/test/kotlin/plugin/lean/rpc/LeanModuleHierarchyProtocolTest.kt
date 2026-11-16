package plugin.lean.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class LeanModuleHierarchyProtocolTest {
    @Test
    fun testModuleDataSurvivesTheServerAndRemoteIdeBoundaries() {
        val wire = Json.parseToJsonElement("""
            {
                "name": "Example.«Unicode.α»",
                "uri": "file:///project%20folder/Example.lean",
                "data": {"token": [1234567890123456789, "opaque"], "nested": {"value": null}}
            }
        """.trimIndent())
        val module = Json.decodeFromString<LeanModule>(wire.toString())
        val hierarchy = LeanModuleHierarchy("process-generation", module)
        val transported = Json.decodeFromString<LeanModuleHierarchy>(Json.encodeToString(hierarchy))
        assertEquals(hierarchy.serverId, transported.serverId)
        assertEquals(wire, Json.encodeToJsonElement(transported.root))
    }

    @Test
    fun testImportKindsKeepVisibilityAllAndMetaDistinctions() {
        val kinds = mapOf(
            "nonMeta" to LeanImportMetaKind.NON_META,
            "meta" to LeanImportMetaKind.META,
            "full" to LeanImportMetaKind.FULL
        )
        for ((tag, expected) in kinds) {
            for (privateImport in listOf(false, true)) {
                for (allImport in listOf(false, true)) {
                    val wire = Json.parseToJsonElement("""
                        {
                            "module": {"name": "Example", "uri": "file:///Example.lean"},
                            "kind": {"isPrivate": $privateImport, "isAll": $allImport, "metaKind": "$tag"}
                        }
                    """.trimIndent())
                    val imported = Json.decodeFromString<LeanImport>(wire.toString())
                    assertEquals(LeanImportKind(privateImport, allImport, expected), imported.kind)
                    assertEquals(wire, Json.encodeToJsonElement(imported))
                }
            }
        }
    }

    @Test
    fun testMissingIdentityOrUnknownImportKindIsNotInvented() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<LeanModule>("""{"name":"Example"}""")
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<LeanImportKind>("""{"isPrivate":false,"isAll":false,"metaKind":"unknown"}""")
        }
    }

    @Test
    fun testNoModuleAndServerFailureRemainDistinctAcrossRpc() {
        val missing: LeanRpcResult<LeanModuleHierarchy?> = LeanRpcResult.Success(null)
        val failed: LeanRpcResult<LeanModuleHierarchy?> = LeanRpcResult.Failure(
            "The server rejected the request", -32601, Json.parseToJsonElement("""{"operation":"imports"}""")
        )
        for (result in listOf(missing, failed)) {
            val decoded = Json.decodeFromString<LeanRpcResult<LeanModuleHierarchy?>>(
                Json.encodeToString(result)
            )
            assertEquals(result, decoded)
        }
    }
}
