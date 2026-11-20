package plugin.lean.toolchain

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.net.PlatformHttpClient
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.initOnShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.tooling.LeanRelease
import plugin.lean.tooling.LeanReleaseChannel
import plugin.lean.tooling.LeanReleaseIndex
import java.awt.Component
import java.io.IOException
import java.net.URI
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

private suspend fun fetchLeanReleases(): List<LeanRelease> = withTimeoutOrNull(20_000) {
    runInterruptible(Dispatchers.IO) {
        PlatformHttpClient.client().use { client ->
            val request = PlatformHttpClient.requestBuilder(URI.create("https://release.lean-lang.org/"))
                .setHeader("User-Agent", "lean4-jetbrains")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .build()
            LeanReleaseIndex.parse(PlatformHttpClient.send(client, request, PlatformHttpClient.gzipStringBodyHandler()))
        }
    }
} ?: throw IOException("The Lean release list timed out. Check the connection or try again.")

/** Returns the confirmed elan identifier. The caller owns installation and selection in its own project context. */
fun chooseLeanToolchainToInstall(
    project: Project?,
    parent: Component,
    inventory: ToolchainInspection.Available,
    installButtonText: String = "Install"
): String? {
    val unavailable = inventory.active as? ActiveToolchainInfo.Unavailable
    val dialog = LeanToolchainInstallDialog(
        project, parent, inventory.installed.map { it.name }.toSet(), unavailable?.installTarget, installButtonText
    )
    dialog.show()
    return if (dialog.isOK) dialog.selectedToolchain else null
}

internal class LeanToolchainInstallDialog(
    project: Project?,
    parent: Component?,
    private val installed: Set<String>,
    private val requested: String?,
    installButtonText: String = "Install",
    private val fetchReleases: suspend () -> List<LeanRelease> = ::fetchLeanReleases
) : DialogWrapper(project, parent, true, IdeModalityType.IDE) {
    private data class Choice(
        val toolchain: String, val title: String, val channel: LeanReleaseChannel?, val note: String? = null
    )

    private val choices = CollectionListModel<Choice>()
    private val tracking = LeanReleaseChannel.values().map {
        Choice(it.trackingToolchain(), "Latest ${channelLabel(it).lowercase()}", it)
    }
    private var releases = emptyList<Choice>()
    private val channels = ComboBox((listOf(null) + LeanReleaseChannel.values()).toTypedArray()).apply {
        renderer = textListCellRenderer<LeanReleaseChannel?> { it?.let(::channelLabel) ?: "All channels" }
    }
    private val list = JBList(choices).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Lean releases"
        emptyText.text = "No matching releases"
        cellRenderer = object : ColoredListCellRenderer<Choice>() {
            override fun customizeCellRenderer(
                list: JList<out Choice>, value: Choice, index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                append(value.title)
                value.note?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                if (value.toolchain in installed) append(" · Installed", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search versions"
        textEditor.accessibleContext.accessibleName = "Search Lean releases"
    }
    private val customName = JBTextField().apply {
        emptyText.text = "owner/repository:tag"
        accessibleContext.accessibleName = "Custom toolchain name"
    }
    private val status = JBLabel()
    private val retry = ActionLink("Retry") { loadReleases() }
    private val tabs = JBTabbedPane()
    private var loading: Job? = null

    val selectedToolchain: String?
        get() {
            val name = if (tabs.selectedIndex == 1) customName.text.trim() else list.selectedValue?.toolchain
            return name?.takeIf { it.isNotEmpty() && '\u0000' !in it && it !in installed }
        }

    init {
        title = "Install Lean Toolchain"
        setOKButtonText(installButtonText)
        init()
        list.addListSelectionListener { updateInstallButton() }
        channels.addActionListener { showChoices() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = showChoices()
        })
        ScrollingUtil.installActions(list, search.textEditor)
        tabs.addChangeListener { updateInstallButton() }
        customName.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = updateInstallButton()
        })
        showChoices()
        Disposer.register(disposable) { loading?.cancel() }
        loadReleases()
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun createCenterPanel(): JComponent = tabs.apply {
        addTab("Releases", panel {
            row("Channel:") { cell(channels) }
            row { cell(search).align(Align.FILL) }
            row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
            row {
                cell(status)
                cell(retry)
            }
        })
        addTab("Custom", panel {
            row("Toolchain:") { cell(customName).align(Align.FILL) }
            row { comment("Enter an elan toolchain identifier for a fork or an unlisted build.") }
        })
        preferredSize = JBDimension(510, 340)
    }

    private fun updateInstallButton() {
        isOKActionEnabled = selectedToolchain != null
    }

    private fun loadReleases() {
        loading?.cancel()
        retry.isVisible = false
        setErrorText(null)
        status.text = "Loading Lean releases…"
        loading = tabs.initOnShow("Load Lean releases") {
            try {
                releases = fetchReleases().map {
                    Choice(it.toolchain(), it.name(), it.channel(), channelLabel(it.channel()))
                }
                status.text = "${releases.size} published releases"
            } catch (error: IOException) {
                status.text = "Could not load the release list."
                setErrorText(error.message ?: "Check the connection to release.lean-lang.org.")
                retry.isVisible = true
            }
            showChoices()
        }
    }

    private fun showChoices() {
        val selected = list.selectedValue?.toolchain ?: requested
        val query = search.text.trim()
        val channel = channels.selectedItem as? LeanReleaseChannel
        val visible = (tracking + releases).filter { channel == null || it.channel == channel }
        val options = buildList {
            if (requested != null && (channel == null || visible.any { it.toolchain == requested })) {
                add(Choice(requested, requested, null, "Requested toolchain"))
            }
            addAll(visible.filter { it.toolchain != requested })
        }
        val matches = options.filter {
            it.toolchain.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true)
        }
        choices.replaceAll(matches)
        if (matches.isNotEmpty()) {
            list.selectedIndex = matches.indexOfFirst { it.toolchain == selected }.coerceAtLeast(0)
        }
        updateInstallButton()
    }

    private fun channelLabel(channel: LeanReleaseChannel): String = when (channel) {
        LeanReleaseChannel.STABLE -> "Stable release"
        LeanReleaseChannel.BETA -> "Release candidate"
        LeanReleaseChannel.NIGHTLY -> "Nightly"
    }
}
