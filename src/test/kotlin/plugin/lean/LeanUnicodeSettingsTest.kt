package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import plugin.lean.language.UnicodeSymbolDialog
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings
import plugin.lean.settings.UnicodeInputConfigurable
import plugin.lean.settings.UnicodeInputConfiguration
import plugin.lean.settings.UnicodeInputSettings
import javax.swing.JTable
import javax.swing.JButton
import javax.swing.JComponent

class LeanUnicodeSettingsTest : BasePlatformTestCase() {
    private lateinit var previousConfiguration: UnicodeInputConfiguration

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        previousConfiguration = service<UnicodeInputSettings>().configuration
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration())
    }

    override fun tearDown() {
        try {
            service<UnicodeInputSettings>().update(previousConfiguration)
        } finally {
            super.tearDown()
        }
    }

    fun testPersistenceAndCallerMutationCannotChangeAnExistingCatalog() {
        val translations = linkedMapOf("custom" to "«\$CURSOR»")
        val settings = service<UnicodeInputSettings>()
        val snapshot = UnicodeInputConfiguration(false, false, ";", translations)
        settings.update(snapshot)
        translations["custom"] = "changed"
        val stored = settings.state
        val serialized = XmlSerializer.serialize(stored)
        stored.customTranslations.clear()
        assertEquals("«»", snapshot.catalog.replacement("custom").orElseThrow().text())
        settings.loadState(XmlSerializer.deserialize(serialized, UnicodeInputSettings.StoredState::class.java))
        assertFalse(settings.configuration.enabled)
        assertFalse(settings.configuration.eagerReplacement)
        assertEquals(";", settings.configuration.leader)
        assertEquals(1, settings.configuration.catalog.replacement("custom").orElseThrow().caretOffset())
        settings.noStateLoaded()
        assertTrue(settings.configuration.enabled)
        assertEquals("\\", settings.configuration.leader)
        assertTrue(settings.configuration.customTranslations.isEmpty())
    }

    fun testInvalidStoredStateDoesNotPartiallyReplaceTheCurrentConfiguration() {
        val settings = service<UnicodeInputSettings>()
        val before = settings.configuration
        for (leader in listOf("", "ab", " ", "\n", "\uD83E")) {
            val invalid = settings.state.also { it.leader = leader; it.enabled = false }
            assertThrows(IllegalArgumentException::class.java) { settings.loadState(invalid) }
            assertSame(before, settings.configuration)
        }
        val invalid = settings.state.also { it.customTranslations["bad"] = "\$CURSOR\$CURSOR" }
        assertThrows(IllegalArgumentException::class.java) { settings.loadState(invalid) }
        assertSame(before, settings.configuration)
    }

    fun testSettingsFormValidatesBeforeApplyingAndResetDiscardsEdits() {
        val settings = service<UnicodeInputSettings>()
        settings.update(UnicodeInputConfiguration(customTranslations = linkedMapOf("one" to "①", "two" to "②")))
        val configurable = UnicodeInputConfigurable()
        try {
            val component = configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified)
            val leader = UIUtil.findComponentsOfType(component, JBTextField::class.java).single()
            leader.text = ";"
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(";", settings.configuration.leader)
            val table = checkNotNull(UIUtil.findComponentOfType(component, JTable::class.java))
            table.model.setValueAt("one", 1, 0)
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(setOf("one", "two"), settings.configuration.customTranslations.keys)
            configurable.reset()
            assertFalse(configurable.isModified)
            assertEquals("two", table.model.getValueAt(1, 0))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testRegisteredUnicodeDocumentationEscapesCustomAliasesAndRetainsItsSnapshot() {
        val settings = service<UnicodeInputSettings>()
        settings.update(UnicodeInputConfiguration(customTranslations = mapOf("<custom>" to "α")))
        myFixture.configureByText("Documentation.lean", "α<caret>")
        val targets = DocumentationTargetProvider.EP_NAME.extensionList.flatMap {
            it.documentationTargets(myFixture.file, 0)
        }
        val target = targets.single()
        val hint = checkNotNull(target.computeDocumentationHint())
        assertTrue(hint.contains("\\alpha"))
        assertTrue(hint.contains("\\&lt;custom&gt;"))
        assertFalse(hint.contains("<custom>"))
        val pointer = target.createPointer()
        settings.update(UnicodeInputConfiguration(leader = ";"))
        assertEquals(hint, pointer.dereference()?.computeDocumentationHint())
    }

    fun testEditingAMultilineReplacementPreservesItsWhitespace() {
        val replacement = "first\n\n  \$CURSOR\nlast\n"
        val settings = service<UnicodeInputSettings>()
        settings.update(UnicodeInputConfiguration(customTranslations = mapOf("block" to replacement)))
        val configurable = UnicodeInputConfigurable()
        try {
            val component = configurable.createComponent()
            configurable.reset()
            val table = checkNotNull(UIUtil.findComponentOfType(component, JTable::class.java))
            assertTrue(table.editCellAt(0, 1))
            assertTrue(table.cellEditor.stopCellEditing())
            configurable.apply()
            assertEquals(replacement, settings.configuration.customTranslations["block"])
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testDocumentationAtSymbolBoundariesPrefersTheFollowingSymbol() {
        val cases = listOf(
            Triple("α", 1, "α"),
            Triple("α\n", 1, "α"),
            Triple("αβ", 1, "β"),
            Triple("𝔸", 2, "𝔸")
        )
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(customTranslations = mapOf("A" to "𝔸")))
        for ((text, offset, symbol) in cases) {
            myFixture.configureByText("Documentation.lean", text)
            val targets = DocumentationTargetProvider.EP_NAME.extensionList.flatMap {
                it.documentationTargets(myFixture.file, offset)
            }
            val hint = checkNotNull(targets.single().computeDocumentationHint())
            assertTrue(hint, hint.contains(symbol))
        }
    }

    fun testSymbolBrowserFiltersByAliasAndSymbolAndInsertsIntoASelection() {
        myFixture.configureByText("Input.lean", "<selection>replace<caret></selection>")
        val dialog = UnicodeSymbolDialog(project, myFixture.editor)
        try {
            val search = dialog.preferredFocusedComponent as JBTextField
            var root: java.awt.Component = search
            while (root.parent != null) root = root.parent
            val table = checkNotNull(UIUtil.findComponentOfType(root as JComponent, JTable::class.java))
            search.text = "α"
            assertTrue(table.rowCount > 0)
            for (row in 0 until table.rowCount) assertTrue(table.model.getValueAt(row, 0).toString().contains("α"))
            search.text = "\\<>"
            val row = (0 until table.rowCount).first { table.model.getValueAt(it, 1) == "\\<>" }
            table.setRowSelectionInterval(row, row)
            UIUtil.findComponentsOfType(root, JButton::class.java).single { it.text == "Insert" }.doClick()
            myFixture.checkResult("⟨<caret>⟩")
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
