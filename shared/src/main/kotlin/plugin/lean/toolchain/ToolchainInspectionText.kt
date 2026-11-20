package plugin.lean.toolchain

import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.rpc.ToolchainSelectionOrigin
import plugin.lean.tooling.ElanSelectionKind

/** The resolution and its source come first so they stay visible in a narrow tool window or wizard. */
fun ToolchainInspection.describe(selection: String): String = when (this) {
    is ToolchainInspection.Failed -> message
    is ToolchainInspection.Available -> buildString {
        val resolved = active
        when (resolved) {
            is ActiveToolchainInfo.Available -> {
                append("Selected toolchain · ${resolved.toolchain.name}")
                if (resolved.cached) append(" (cached channel resolution)")
            }
            is ActiveToolchainInfo.Unavailable -> append(resolved.message)
        }
        val source = if (selection.isNotEmpty()) "Selected explicitly in the IDE" else origin?.description()
        if (source != null) append("\n").append(source)
        if (resolved is ActiveToolchainInfo.Unavailable && resolved.installTarget != null) {
            append("\nChoose Install Toolchain to install ${resolved.installTarget}.")
        }
        val count = if (installed.size == 1) "1 registered toolchain" else "${installed.size} registered toolchains"
        append("\n\nElan $elanVersion · $count\n$executable")
        if (resolved is ActiveToolchainInfo.Available) append("\nToolchain directory · ${resolved.toolchain.path}")
        append("\nInspected in $directory")
    }
}

private fun ToolchainSelectionOrigin.description(): String {
    val label = when (kind) {
        ElanSelectionKind.DEFAULT -> "Elan default"
        ElanSelectionKind.ENVIRONMENT -> "Selected by ELAN_TOOLCHAIN"
        ElanSelectionKind.CONFIGURATION_FILE -> "Selected by the toolchain file"
        ElanSelectionKind.DIRECTORY_OVERRIDE -> "Elan directory override"
        ElanSelectionKind.TOOLCHAIN_DIRECTORY -> "Inside the installed toolchain directory"
    }
    return if (location == null) label else "$label · $location"
}
