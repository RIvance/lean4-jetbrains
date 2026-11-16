package plugin.lean

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings
import plugin.lean.toolchain.LeanToolchainService
import java.nio.file.Files
import java.nio.file.Path

/** Checks native project/library classification without starting Lean or inspecting installed toolchains. */
class LeanProjectRootsTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var root: VirtualFile
    private val index get() = ProjectFileIndex.getInstance(project)

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        directory = Files.createTempDirectory("lean project roots ")
        root = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory))
        ModuleRootModificationUtil.addContentRoot(module, root)
        project.service<LeanToolchainService>().restart()
    }

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.filter { it.file == root }.forEach(model::removeContentEntry)
            }
            FileUtil.delete(directory.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testLakeMetadataExcludesCachesWithoutChangingUserContentEntries() {
        val artifact = file(".lake/build/Generated.lean")
        val source = file("Main.lean")
        val unrelated = file("plain/.lake/Notes.lean")
        assertTrue(index.isInContent(artifact))
        file("lakefile.toml", "name = \"example\"")
        awaitExcluded(artifact, true)
        assertTrue(index.isInContent(source))
        assertTrue(index.isInContent(unrelated))
        assertFalse(ModuleRootManager.getInstance(module).contentEntries.any {
            it.excludeFolderUrls.contains(root.url + "/.lake")
        })
    }

    fun testCacheCreatedAfterProjectDetectionIsExcludedImmediately() {
        file("lakefile.lean", "import Lake")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val artifact = file(".lake/build/Generated.lean")
        awaitExcluded(artifact, true)
    }

    fun testNestedLakeProjectsAreRecognizedWithoutOpeningTheirFiles() {
        file("nested/lakefile.toml", "name = \"nested\"")
        val artifact = file("nested/.lake/build/Generated.lean")
        val source = file("nested/Main.lean")
        awaitExcluded(artifact, true)
        assertTrue(index.isInContent(source))
    }

    fun testRemovingOrRenamingLakeConfigurationReleasesAutomaticExclusions() {
        val configuration = file("nested/lakefile.toml", "name = \"nested\"")
        val artifact = file("nested/.lake/build/Generated.lean")
        awaitExcluded(artifact, true)
        runWriteAction { configuration.rename(this, "saved.toml") }
        awaitExcluded(artifact, false)
        runWriteAction { configuration.rename(this, "lakefile.toml") }
        awaitExcluded(artifact, true)
        runWriteAction { configuration.delete(this) }
        awaitExcluded(artifact, false)
    }

    fun testDependencySourceRootsRemainLibrariesInsideExcludedCaches() {
        file("lakefile.toml", "name = \"example\"")
        val source = file(".lake/packages/dependency/Source.lean")
        val artifact = file(".lake/build/Generated.lean")
        awaitExcluded(artifact, true)
        val library = SyntheticLibrary.newImmutableLibrary(listOf(source.parent))
        val provider = object : AdditionalLibraryRootsProvider() {
            override fun getAdditionalProjectLibraries(project: Project) = listOf(library)
        }
        val extension = AdditionalLibraryRootsProvider.EP_NAME
        ExtensionTestUtil.maskExtensions(extension, extension.extensionList + provider, testRootDisposable)
        runWriteAction {
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                project, "Dependency fixture", emptyList(), listOf(source.parent), "Lean roots test"
            )
        }
        PlatformTestUtil.waitWithEventsDispatching("Dependency source is not a library", {
            index.isInLibrarySource(source)
        }, 10)
        assertFalse(index.isInContent(source))
        assertFalse(index.isExcluded(source))
        assertTrue(index.isExcluded(artifact))
    }

    private fun file(relativePath: String, text: String = "def value := 1"): VirtualFile = runWriteAction {
        val directoryPath = relativePath.substringBeforeLast('/', "")
        val parent = if (directoryPath.isEmpty()) root else {
            checkNotNull(VfsUtil.createDirectoryIfMissing(root, directoryPath))
        }
        parent.createChildData(this, relativePath.substringAfterLast('/')).also { VfsUtil.saveText(it, text) }
    }

    private fun awaitExcluded(file: VirtualFile, expected: Boolean) {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        PlatformTestUtil.waitWithEventsDispatching("Incorrect exclusion for ${file.path}", {
            index.isExcluded(file) == expected && index.isInContent(file) != expected
        }, 10)
    }
}
