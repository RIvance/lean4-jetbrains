package plugin.lean.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanImportOptimizerTest {
    private val gson = MessageJsonHandler(emptyMap()).gson
    private val uri = "file:///workspace/Main.lean"

    @Test
    fun `versioned imports preserve edits and annotations without an invented fallback`() {
        val action = action(
            """
            "documentChanges": [{
                "textDocument": {"uri": "$uri", "version": 7},
                "edits": [{
                    "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}},
                    "newText": "import Library.Types\n"
                }]
            }],
            "changeAnnotations": {"imports": {"label": "Add required imports"}}
            """
        )
        assertTrue(action.edit.changes.isEmpty())

        val selected = checkNotNull(importEdits(action, uri))
        assertNull(selected.changes)
        val document = selected.documentChanges.single().left
        assertEquals(7, document.textDocument.version)
        assertEquals("import Library.Types\n", document.edits.single().newText)
        assertEquals("Add required imports", selected.changeAnnotations["imports"]!!.label)
        assertNotNull(action.edit.changes)
    }

    @Test
    fun `versioned edits take precedence over a supplied unversioned fallback`() {
        val action = action(
            """
            "changes": {"file:///fallback.lean": []},
            "documentChanges": [{"textDocument": {"uri": "$uri", "version": null}, "edits": []}]
            """
        )
        val selected = checkNotNull(importEdits(action, uri))
        assertNull(selected.changes)
        assertEquals(uri, selected.documentChanges.single().left.textDocument.uri)
        action.edit.documentChanges = emptyList()
        assertNull(importEdits(action, uri))
    }

    @Test
    fun `unversioned import edits remain supported`() {
        val action = action(""""changes": {"$uri": []}""")
        val selected = checkNotNull(importEdits(action, uri))
        assertNull(selected.documentChanges)
        assertEquals(setOf(uri), selected.changes.keys)
    }

    @Test
    fun `import optimization cannot change other files or perform resource operations`() {
        val edits = listOf(
            """"changes": {"$uri": [], "file:///Other.lean": []}""",
            """
            "documentChanges": [{"textDocument": {"uri": "file:///Other.lean", "version": null}, "edits": []}]
            """,
            """"documentChanges": [{"kind": "create", "uri": "$uri"}]"""
        )
        for (edit in edits) assertNull(importEdits(action(edit), uri))
    }

    @Test
    fun `unrelated disabled and command actions cannot run as import optimization`() {
        val action = action(""""changes": {"$uri": []}""")
        action.kind = "quickfix"
        assertNull(importEdits(action, uri))
        action.kind = "source.organizeImports"
        action.disabled = CodeActionDisabled("The source changed")
        assertNull(importEdits(action, uri))
        action.disabled = null
        action.command = Command("Change project", "workspace.change")
        assertNull(importEdits(action, uri))
    }

    private fun action(edit: String): CodeAction = gson.fromJson(
        """{"title": "Import unknown identifiers", "kind": "source.organizeImports", "edit": {$edit}}""",
        CodeAction::class.java
    )
}
