package plugin.lean.toolchain

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.InstalledToolchainInfo
import plugin.lean.rpc.ToolchainInspection

class LeanToolchainComboBoxTest : BasePlatformTestCase() {
    fun testLongInstalledNamesAndAutomaticPathsFitTheSameFormWidth() {
        val selector = LeanToolchainComboBox()
        val width = selector.minimumSize.width
        val path = "/" + "a local toolchain directory/".repeat(12) + "lean"
        val toolchain = InstalledToolchainInfo(path, path)
        val inventory = ToolchainInspection.Available(
            "elan", "4.2.1", "/project", listOf(toolchain), ActiveToolchainInfo.Available(toolchain, false)
        )
        selector.showInspection(inventory, "")
        assertEquals(width, selector.minimumSize.width)
        assertEquals("", selector.toolchainName)
        selector.selectedItem = path
        assertEquals(width, selector.minimumSize.width)
        assertEquals(path, selector.toolchainName)
    }
}
