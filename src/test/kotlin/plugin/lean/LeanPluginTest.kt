package plugin.lean

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.language.LeanFileType
import plugin.lean.language.LeanLanguage
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings
import plugin.lean.toolchain.LeanToolchainService

/** Loads the assembled plugin's registrations and services in the IntelliJ test application. */
class LeanPluginTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
    }

    fun testLeanFilesUseRegisteredLanguageAndEditorServices() {
        myFixture.configureByText("Example.lean", "example (n : Nat) : n = n := by\n  rfl\n")
        assertSame(LeanFileType, myFixture.file.fileType)
        assertSame(LeanLanguage, myFixture.file.language)
        assertNotNull(LanguageParserDefinitions.INSTANCE.forLanguage(LeanLanguage))
        assertNotNull(LanguageCommenters.INSTANCE.forLanguage(LeanLanguage))
        assertNotNull(LanguageBraceMatching.INSTANCE.forLanguage(LeanLanguage))
        assertNotNull(SyntaxHighlighterFactory.getSyntaxHighlighter(LeanLanguage, project, myFixture.file.virtualFile))
        assertEquals("example (n : Nat) : n = n := by\n  rfl\n", myFixture.file.text)
    }

    fun testOnlyRiderAddsADeclarationHandlerForLean() {
        val leanHandler = GotoDeclarationHandler.EP_NAME.extensionList.any {
            it.javaClass.packageName.startsWith("plugin.lean.")
        }
        assertEquals(ApplicationInfo.getInstance().build.productCode == "RD", leanHandler)
    }

    fun testProjectConfigurationUsesIndependentImmutableSnapshots() {
        val settings = project.service<LeanSettings>()
        val initial = settings.configuration
        val selected = LeanConfiguration(
            elanExecutable = "/configured/elan", toolchain = "local-build", enabled = false
        )
        settings.update(selected)
        val stored = settings.state
        assertEquals(selected, project.service<LeanToolchainService>().configuration)
        assertEquals(LeanConfiguration(enabled = false), initial)
        settings.update(selected.copy(toolchain = "another-build"))
        settings.loadState(stored)
        assertEquals(selected, settings.configuration)
        stored.attributes.clear()
        assertEquals(selected, settings.configuration)
    }

    fun testRetainedProcessSettingsDoNotShareMutableCollectionsWithCallersOrPersistence() {
        val arguments = mutableListOf("-j", "2")
        val environment = linkedMapOf("LEAN_SAMPLE" to "saved")
        val settings = project.service<LeanSettings>()
        settings.update(LeanConfiguration(enabled = false, serverArguments = arguments, environment = environment))
        val retained = settings.configuration
        arguments[1] = "8"
        environment["LEAN_SAMPLE"] = "changed"
        val stored = settings.state
        settings.update(LeanConfiguration(enabled = false, serverArguments = arguments, environment = environment))
        assertEquals(listOf("-j", "2"), retained.serverArguments)
        assertEquals("saved", retained.environment["LEAN_SAMPLE"])
        settings.loadState(stored)
        stored.attributes.clear()
        assertEquals(listOf("-j", "2"), settings.configuration.serverArguments)
        assertEquals("saved", settings.configuration.environment["LEAN_SAMPLE"])
        assertEquals(listOf("-j", "2"), retained.serverArguments)
        assertEquals("saved", retained.environment["LEAN_SAMPLE"])
    }

    fun testInMemoryProjectFilesDoNotRequireHostFilesystemPaths() {
        val configuration = myFixture.addFileToProject("lakefile.toml", "name = \"example\"")
        val file = myFixture.addFileToProject("nested/Main.lean", "def value := 1")
        assertFalse(configuration.virtualFile.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL)
        assertEquals("def value := 1", file.text)
    }

    fun testInfoViewCommandsAreAvailableOnlyAfterADocumentIsDisplayed() {
        val manager = ActionManager.getInstance()
        val window = ToolWindowManager.getInstance(project).getToolWindow("Lean")
        val contents = window?.contentManagerIfCreated
        val context = SimpleDataContext.getProjectContext(project)
        for (id in listOf(
            "Lean.ReloadInfoView",
            "Lean.InfoView.TogglePin", "Lean.InfoView.TogglePaused", "Lean.InfoView.ToggleAllMessages",
            "Lean.InfoView.ToggleExpectedType", "Lean.InfoView.CopyToComment"
        )) {
            val action = checkNotNull(manager.getAction(id))
            val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
            action.update(event)
            assertFalse(event.presentation.isEnabled)
        }
        assertSame(contents, window?.contentManagerIfCreated)
        val show = checkNotNull(manager.getAction("Lean.ShowInfoView"))
        val event = AnActionEvent.createEvent(show, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        show.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testLakeProjectCommandsRequireAnOpenProject() {
        val manager = ActionManager.getInstance()
        for (id in listOf(
            "Lean.Project.Build", "Lean.Project.Clean", "Lean.Project.Test", "Lean.Project.Update",
            "Lean.Project.FetchCache", "Lean.Project.FetchOpenFileCaches"
        )) {
            val action = checkNotNull(manager.getAction(id))
            for (hasProject in listOf(false, true)) {
                val context = if (hasProject) {
                    SimpleDataContext.getProjectContext(project)
                } else {
                    SimpleDataContext.EMPTY_CONTEXT
                }
                val event = AnActionEvent.createEvent(
                    action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
                )
                action.update(event)
                assertEquals(hasProject, event.presentation.isEnabled)
                assertFalse(event.presentation.text.isNullOrBlank())
            }
        }
    }

    fun testSelectedFileCacheActionRequiresOnlyLeanSourceFiles() {
        val action = checkNotNull(ActionManager.getInstance().getAction("Lean.Project.FetchSelectedFileCaches"))
        val lean = myFixture.addFileToProject("Main.lean", "example : True := by trivial").virtualFile
        val text = myFixture.addFileToProject("README.md", "Project").virtualFile
        for ((files, enabled) in listOf(
            emptyArray<com.intellij.openapi.vfs.VirtualFile>() to false,
            arrayOf(lean) to true, arrayOf(lean, text) to false, arrayOf(lean.parent) to false
        )) {
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files)
                .build()
            val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
            action.update(event)
            assertEquals(enabled, event.presentation.isEnabled)
        }
    }

    fun testProofFoldingCommandsRequireALeanEditorAndAppearInTheFoldingMenu() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("FoldingGroup") as ActionGroup
        for (filename in listOf("Example.lean", "Example.txt")) {
            myFixture.configureByText(filename, "example : True := by\n  trivial")
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
                .build()
            for (id in listOf("Lean.FoldAllProofs", "Lean.UnfoldAllProofs")) {
                val action = checkNotNull(manager.getAction(id))
                assertTrue(group.getChildren(null).contains(action))
                val event = AnActionEvent.createEvent(
                    action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
                )
                action.update(event)
                assertEquals(filename.endsWith(".lean"), event.presentation.isEnabledAndVisible)
            }
        }
    }
}
