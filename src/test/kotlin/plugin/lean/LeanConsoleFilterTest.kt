package plugin.lean

import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.run.LeanConsoleFilter
import plugin.lean.settings.LeanSettings
import java.nio.file.Files
import java.nio.file.Path

class LeanConsoleFilterTest : BasePlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        directory = Files.createTempDirectory("lean console ")
    }

    override fun tearDown() {
        try { FileUtil.delete(directory.toFile()) } finally { super.tearDown() }
    }

    fun testRelativeAbsoluteAndLakeLocationsNavigatePastSupplementaryCharacters() {
        val path = Files.writeString(directory.resolve("Proof α.lean"), "-- comment\nexample := \"𝒜\" + unknown\n")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        val offset = document.text.indexOf("unknown")
        val column = document.text.codePointCount(document.getLineStartOffset(1), offset)
        val filter = LeanConsoleFilter(project, directory)
        for (filename in listOf(path.fileName.toString(), path.toString())) {
            for (prefix in listOf("", "error: ", "warning: ", "info: ", "trace: ")) {
                val line = "$prefix$filename:2:$column-2:${column + 7}: error: unknown identifier\n"
                val result = checkNotNull(filter.applyFilter(line, 100 + line.length))
                val item = result.resultItems.single()
                assertEquals(100 + prefix.length, item.highlightStartOffset)
                assertEquals(100 + line.indexOf(": error:"), item.highlightEndOffset)
                val descriptor = checkNotNull((item.hyperlinkInfo as FileHyperlinkInfo).descriptor)
                assertEquals(file, descriptor.file)
                assertEquals(offset, descriptor.offset)
            }
        }
    }

    fun testMalformedLocationsAndOverflowRemainPlainConsoleText() {
        val filter = LeanConsoleFilter(project, directory)
        for (line in listOf("Build completed", "Main.lean:0:2: error", "Main.lean:1:999999999999: error")) {
            assertNull(filter.applyFilter(line, line.length))
        }
    }
}
