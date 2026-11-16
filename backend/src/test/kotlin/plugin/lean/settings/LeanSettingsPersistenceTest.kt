package plugin.lean.settings

import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import plugin.lean.persistence.encodeLiteralText
import plugin.lean.rpc.LeanConfiguration

class LeanSettingsPersistenceTest : BasePlatformTestCase() {
    fun testLiteralProcessSettingsSurviveNativePathMacroProcessing() {
        val expected = LeanConfiguration(
            elanExecutable = "/tools/\$USER_HOME\$/elan",
            toolchain = "linked-\$USER_HOME\$",
            enabled = false,
            serverArguments = listOf("", "\$USER_HOME\$", "  spaces  ", "line one\r\nline two\t", "∀ 𝒜", "\uD800"),
            environment = mapOf("\$USER_HOME\$" to "literal \$USER_HOME\$", "LEAN_SAMPLE" to "\${value}")
        )
        val settings = LeanSettings().apply { update(expected) }
        val stored = settings.state
        val macros = PathMacroManager.getInstance(project)
        assertFalse(macros.expandPath("\$USER_HOME\$") == "\$USER_HOME\$")
        macros.collapsePaths(stored)
        val reloaded = JDOMUtil.load(JDOMUtil.write(stored))
        macros.expandPaths(reloaded)
        val restored = LeanSettings()
        restored.loadState(reloaded)
        assertEquals(expected, restored.configuration)
        assertEquals(expected, settings.configuration)
    }

    fun testExistingWorkspaceSettingsMigrateWithoutLosingValues() {
        val stored = JDOMUtil.load("""
            <component name="LeanSettings">
              <option name="elanExecutable" value="/tools/elan" />
              <option name="toolchain" value="linked-build" />
              <option name="enabled" value="false" />
              <option name="serverArguments">
                <list><option value="-j" /><option value="2" /><option value="" /></list>
              </option>
              <option name="environment">
                <map><entry key="LEAN_SAMPLE" value="a &amp; b" /></map>
              </option>
            </component>
        """.trimIndent())
        val settings = LeanSettings()
        settings.loadState(stored)
        val expected = LeanConfiguration(
            elanExecutable = "/tools/elan",
            toolchain = "linked-build",
            enabled = false,
            serverArguments = listOf("-j", "2", ""),
            environment = mapOf("LEAN_SAMPLE" to "a & b")
        )
        assertEquals(expected, settings.configuration)
        val restored = LeanSettings()
        restored.loadState(JDOMUtil.load(JDOMUtil.write(settings.state)))
        assertEquals(expected, restored.configuration)
        stored.removeContent()
        assertEquals(expected, settings.configuration)
    }

    fun testDefaultsRemainAvailableInExistingAndNewSettings() {
        val settings = LeanSettings()
        settings.update(LeanConfiguration(enabled = false, toolchain = "linked-build"))
        settings.loadState(Element("component"))
        assertEquals(LeanConfiguration(), settings.configuration)
        val stored = settings.state
        settings.update(LeanConfiguration(enabled = false))
        settings.loadState(stored)
        assertEquals(LeanConfiguration(), settings.configuration)
    }

    fun testInvalidSavedValuesLeaveTheEstablishedConfigurationIntact() {
        val settings = LeanSettings()
        settings.update(LeanConfiguration(toolchain = "linked-build", environment = mapOf("LEAN_SAMPLE" to "saved")))
        val retained = settings.configuration
        for (encoded in listOf(
            "%", "AA==", encodeLiteralText("{"), encodeLiteralText("""{"environment":{"A":null}}""")
        )) {
            val stored = Element("component").setAttribute("literalConfiguration", encoded)
            val failure = org.junit.Assert.assertThrows(InvalidDataException::class.java) { settings.loadState(stored) }
            assertNotNull(failure.cause)
            assertSame(retained, settings.configuration)
        }
    }
}
