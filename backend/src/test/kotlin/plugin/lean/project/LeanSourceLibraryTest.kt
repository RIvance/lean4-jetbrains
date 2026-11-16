package plugin.lean.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class LeanSourceLibraryTest : BasePlatformTestCase() {
    fun testCacheDirectoriesAreExcludedWithoutHidingLibraryRootsOrOrdinarySources() {
        val source = myFixture.addFileToProject("dependency/Source.lean", "def value := 1").virtualFile
        val cacheFile = myFixture.addFileToProject("dependency/.lake/build/Generated.lean", "").virtualFile
        val root = source.parent
        val library = LeanSourceLibrary(Path.of("workspace"), listOf(root))
        val condition = checkNotNull(library.unitedExcludeCondition)
        assertFalse(condition.value(root))
        assertFalse(condition.value(source))
        val cache = cacheFile.parent.parent
        assertTrue(condition.value(cache))

        val explicitRoot = LeanSourceLibrary(Path.of("workspace"), listOf(cache))
        assertFalse(checkNotNull(explicitRoot.unitedExcludeCondition).value(cache))
    }
}
