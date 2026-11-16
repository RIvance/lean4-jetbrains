package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.search.LoogleClient
import plugin.lean.search.LoogleHit
import plugin.lean.search.LooglePanel
import plugin.lean.search.LoogleResponse
import plugin.lean.search.LoogleSearch
import plugin.lean.settings.LeanSettings
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import javax.swing.JButton
import javax.swing.JTable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LeanLoogleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
    }

    fun testProtocolDistinguishesMatchesQueryProblemsAndMalformedResponses() {
        val matches = LoogleResponse.parse("""
            {"count": 3, "header": "Found three declarations", "futureField": true, "hits": [
                {"name": "Example.α", "module": "Example.Core", "type": " : α → α", "doc": null},
                {"name": "Example.other", "module": "Example.Core", "type": " : Nat", "doc": "Documentation"}
            ]}
        """) as LoogleResponse.Matches
        assertEquals(3, matches.count)
        assertNull(matches.hits.first().documentation)
        assertEquals("Documentation", matches.hits.last().documentation)
        val problem = LoogleResponse.parse("""{"error":"Unknown name", "suggestions":["\"name\""]}""")
        assertEquals(LoogleResponse.Problem("Unknown name", listOf("\"name\"")), problem)
        val empty = LoogleResponse.parse("""{"count":0, "header":"No matches", "hits":[]}""")
        assertEquals(LoogleResponse.Matches(0, "No matches", emptyList()), empty)
        for (invalid in listOf(
            "null", "{}", "[]", "not JSON", """{"error":false}""", """{"error":"Bad", "suggestions":false}""",
            """{"count":-1, "header":"Bad", "hits":[]}""",
            """{"count":"0", "header":"Bad", "hits":[]}""",
            """{"count":0.5, "header":"Bad", "hits":[]}""",
            """{"count":1, "header":"Bad", "hits":[{"name":"Incomplete"}]}"""
        )) {
            assertThrows(IOException::class.java) { LoogleResponse.parse(invalid) }
        }
    }

    fun testQueryAndDocumentationLinksEncodeTextWithoutChangingTheirDestinations() {
        val query = "α → β, \"name&lucky=yes#fragment\""
        val uri = LoogleClient.queryUri(query)
        assertEquals("loogle.lean-lang.org", uri.host)
        assertEquals("/json", uri.path)
        assertFalse(uri.rawQuery.contains('&'))
        assertEquals(query, URLDecoder.decode(uri.rawQuery.removePrefix("q="), UTF_8))
        val link = LoogleHit("Example.α'", "Example.Core", " : Nat", null).documentationUri
        assertEquals("https", link.scheme)
        assertEquals("leanprover-community.github.io", link.host)
        assertEquals("/mathlib4_docs/Example/Core.html", link.path)
        assertEquals("Example.α'", link.fragment)
    }

    fun testLaterSearchCancellationAndDisposalPreventStaleResults() {
        val pending = mutableMapOf<String, Continuation<LoogleResponse>>()
        val parent = CoroutineScope(SupervisorJob())
        val panel = LooglePanel(project, parent, LoogleSearch { query ->
            suspendCoroutine { pending[query] = it }
        })
        try {
            val table = checkNotNull(UIUtil.findComponentOfType(panel, JTable::class.java))
            assertTrue(pending.isEmpty())
            panel.submit("older")
            await { pending.containsKey("older") }
            panel.submit("newer")
            await { pending.containsKey("newer") }
            pending.remove("newer")!!.resume(matches("Newer"))
            await { table.rowCount == 1 }
            pending.remove("older")!!.resume(matches("Older"))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("Newer", table.getValueAt(0, 0))
            panel.submit("cancelled")
            await { pending.containsKey("cancelled") }
            panel.cancelSearch()
            pending.remove("cancelled")!!.resume(matches("Cancelled"))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(0, table.rowCount)
            assertEquals("Search cancelled.", status(panel).text)
            panel.submit("disposed")
            await { pending.containsKey("disposed") }
            Disposer.dispose(panel)
            pending.remove("disposed")!!.resume(matches("Disposed"))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(0, table.rowCount)
        } finally {
            Disposer.dispose(panel)
            pending.values.forEach { it.resume(matches("Released")) }
            parent.cancel()
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    fun testSuggestionsHistoryAndTransportFailuresUseNativeControls() {
        val requests = mutableListOf<String>()
        val parent = CoroutineScope(SupervisorJob())
        val panel = LooglePanel(project, parent, LoogleSearch { query ->
            requests.add(query)
            when (query) {
                "unknown" -> LoogleResponse.Problem("Unknown identifier", listOf("\"unknown\""))
                "offline" -> throw IOException("Offline fixture")
                else -> matches(query)
            }
        })
        try {
            panel.submit("unknown")
            await { status(panel).text == "Unknown identifier" }
            val suggestions = checkNotNull(UIUtil.findComponentOfType(panel, JBList::class.java))
            assertEquals("\"unknown\"", suggestions.selectedValue)
            panel.submit("offline")
            await { status(panel).text.contains("Offline fixture") }
            button(panel, "Previous").doClick()
            button(panel, "Previous").doClick()
            val query = checkNotNull(UIUtil.findComponentOfType(panel, LanguageTextField::class.java))
            assertEquals("unknown", query.text)
            assertEquals(listOf("unknown", "offline"), requests)
            button(panel, "Search").doClick()
            await { requests.size == 3 }
            assertEquals("unknown", requests.last())
        } finally {
            Disposer.dispose(panel)
            parent.cancel()
        }
    }

    private fun matches(name: String) = LoogleResponse.Matches(
        1, "One match", listOf(LoogleHit(name, "Example", " : Nat", null))
    )

    private fun status(panel: LooglePanel) = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single {
        it.accessibleContext.accessibleName == "Loogle search status"
    }

    private fun button(panel: LooglePanel, text: String): JButton {
        return UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == text }
    }

    private fun await(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("Loogle did not update", condition, 10)
}
