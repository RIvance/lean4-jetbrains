package plugin.lean.infoview

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import plugin.lean.rpc.LeanInfoviewApi
import plugin.lean.rpc.LeanInfoviewCommand
import plugin.lean.rpc.LeanInfoviewEvent
import plugin.lean.rpc.LeanInfoviewResponse
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Owns the browser and RPC page on the EDT. Browser creation registers resources with its supplied lifetime,
 * including resources allocated before a failure; a null browser means the runtime does not support JCEF.
 */
internal class LeanInfoviewPanel(
    private val project: Project,
    parent: CoroutineScope,
    private val createBrowser: (Disposable) -> JBCefBrowser? = ::createInfoviewBrowser
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.EDT
    )
    private val json = Json { encodeDefaults = true }
    private val error = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "InfoView connection status"
    }
    private val errorPanel = JPanel(BorderLayout()).apply { isVisible = false }
    private var browser: JBCefBrowser? = null
    private var page = Page()

    val focusComponent: JComponent get() = browser?.component ?: error
    val canReload: Boolean get() = scope.isActive
    val canRequestAction: Boolean get() = scope.isActive && page.scope.isActive && page.controlsAvailable

    /** A reload ends the subscription and every pending request before establishing a new page identity. */
    private inner class Page {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val scope = CoroutineScope(
            this@LeanInfoviewPanel.scope.coroutineContext +
            SupervisorJob(this@LeanInfoviewPanel.scope.coroutineContext[Job])
        )
        val requests = mutableMapOf<String, Job>()
        var subscribed = false
        var controlsAvailable = false
    }

    init {
        errorPanel.add(error, BorderLayout.CENTER)
        errorPanel.add(JButton("Reload InfoView").apply {
            addActionListener { reload() }
        }, BorderLayout.EAST)
        add(errorPanel, BorderLayout.NORTH)
        try {
            initializeBrowser()
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }

    private fun initializeBrowser() {
        val lifetime = Disposer.newDisposable("Lean InfoView browser")
        var candidate: JBCefBrowser? = null
        var initialized = false
        try {
            candidate = createBrowser(lifetime)
            if (candidate == null) {
                showError(
                    "InfoView requires a JetBrains Runtime with JCEF. Select the IDE's bundled runtime in Help → " +
                    "Choose Boot Java Runtime for the IDE."
                )
                return
            }
            configureBrowser(candidate, lifetime)
            add(candidate.component, BorderLayout.CENTER)
            browser = candidate
            candidate.loadURL(InfoviewResources.INDEX)
            Disposer.register(this, lifetime)
            initialized = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: IllegalStateException) {
            browserFailed(exception)
        } catch (exception: UnsatisfiedLinkError) {
            browserFailed(exception)
        } finally {
            if (!initialized) {
                browser = null
                page.scope.cancel()
                candidate?.component?.let { remove(it) }
                Disposer.dispose(lifetime)
            }
            revalidate()
            repaint()
        }
    }

    private fun browserFailed(cause: Throwable) {
        logger<LeanInfoviewPanel>().warn("Could not start the InfoView browser", cause)
        showError(
            "InfoView's embedded browser could not start. Try Reload InfoView. If it still fails, restart the IDE " +
            "and check its JCEF runtime in the IDE log. On Linux, missing native libraries can prevent browser startup."
        )
    }

    private fun configureBrowser(browser: JBCefBrowser, lifetime: Disposable) {
        val browserScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        Disposer.register(lifetime, Disposable { browserScope.cancel() })
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        Disposer.register(lifetime, query)
        query.addHandler { raw ->
            try {
                val envelope = Json.parseToJsonElement(raw).jsonObject
                val token = envelope.getValue("token").jsonPrimitive.content
                val message = envelope.getValue("payload").jsonObject
                val id = message.getValue("id").jsonPrimitive.content
                val command = message.getValue("command").jsonObject
                val operation = command.getValue("type").jsonPrimitive.content
                browserScope.launch {
                    val owner = page
                    if (owner.token == token && browser.cefBrowser.url == InfoviewResources.INDEX) {
                        accept(owner, id, command, operation)
                    }
                }
                JBCefJSQuery.Response("")
            } catch (exception: IllegalArgumentException) {
                JBCefJSQuery.Response(null, -32600, "Invalid InfoView bridge message")
            } catch (exception: NoSuchElementException) {
                JBCefJSQuery.Response(null, -32600, "Incomplete InfoView bridge message")
            }
        }
        browser.jbCefClient.addRequestHandler(InfoviewResources(lifetime), browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(
                browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType
            ) {
                if (frame.isMain) browserScope.launch {
                    page.scope.cancel()
                    page = Page()
                }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && frame.url == InfoviewResources.INDEX) browserScope.launch {
                    val failure = "function(code, message) { window.leanReceive({ reply: " +
                        "{ id: payload.id, type: 'error', code, message } }); }"
                    val request = "JSON.stringify({ token: ${JsonPrimitive(page.token)}, payload })"
                    val injection = query.inject(request, "function() {}", failure)
                    // A remote CefFrame is valid only during its callback; the browser owns the lasting handle.
                    browser.executeJavaScript(
                        "window.leanPageToken = ${JsonPrimitive(page.token)};" +
                        "window.leanHost = raw => { const payload = JSON.parse(raw); $injection };" +
                        "window.dispatchEvent(new Event('lean-host-ready'));",
                        InfoviewResources.INDEX, 0
                    )
                    errorPanel.isVisible = false
                    send(buildJsonObject { put("theme", infoviewTheme()) })
                }
            }

            override fun onLoadError(
                browser: CefBrowser, frame: CefFrame, code: CefLoadHandler.ErrorCode, text: String, url: String
            ) {
                if (frame.isMain && code != CefLoadHandler.ErrorCode.ERR_ABORTED) browserScope.launch {
                    disconnect(page, "InfoView could not load: $text")
                }
            }
        }, browser.cefBrowser)
        ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { browserScope.launch { send(buildJsonObject { put("theme", infoviewTheme()) }) } }
        )
        ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { browserScope.launch { send(buildJsonObject { put("theme", infoviewTheme()) }) } }
        )
    }

    private fun accept(owner: Page, id: String, command: JsonObject, operation: String) {
        if (operation == "cancel") {
            owner.requests[id]?.cancel()
            return
        }
        val job = owner.scope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = execute(owner, command)
                reply(owner, id, response)
            } catch (exception: CancellationException) {
                reply(owner, id, LeanInfoviewResponse.Error(-32800, "Request cancelled"))
                throw exception
            } catch (exception: ProcessCanceledException) {
                reply(owner, id, LeanInfoviewResponse.Error(-32800, "Request cancelled"))
                throw exception
            } catch (exception: SerializationException) {
                reply(owner, id, LeanInfoviewResponse.Error(-32602, "Invalid InfoView command: ${exception.message}"))
            } catch (exception: Exception) {
                logger<LeanInfoviewPanel>().warn("InfoView request failed", exception)
                reply(owner, id, LeanInfoviewResponse.Error(-32603, exception.message ?: "InfoView request failed"))
            } finally {
                owner.requests.remove(id)
            }
        }
        owner.requests[id] = job
        job.start()
    }

    private suspend fun execute(owner: Page, command: JsonObject): LeanInfoviewResponse {
        return when (command.getValue("type").jsonPrimitive.content) {
            "ready" -> {
                check(!owner.subscribed) { "This InfoView page is already connected" }
                owner.subscribed = true
                val ready = CompletableDeferred<Unit>(owner.scope.coroutineContext[Job])
                owner.scope.launch {
                    try {
                        val api = LeanInfoviewApi.getInstance()
                        api.events(project.projectId(), owner.id).collect { event ->
                            if (page === owner) send(buildJsonObject {
                                put("event", json.encodeToJsonElement<LeanInfoviewEvent>(event))
                            })
                            if (event == LeanInfoviewEvent.Ready) ready.complete(Unit)
                        }
                        disconnect(owner, "The InfoView connection ended. Reload InfoView to reconnect.")
                    } catch (exception: CancellationException) {
                        if (owner.scope.isActive) {
                            disconnect(owner, "The InfoView connection was lost. Reload InfoView to reconnect.")
                        }
                        throw exception
                    } catch (exception: ProcessCanceledException) {
                        if (owner.scope.isActive) {
                            disconnect(owner, "The InfoView connection was lost. Reload InfoView to reconnect.")
                        }
                        throw exception
                    } catch (exception: Exception) {
                        logger<LeanInfoviewPanel>().warn("InfoView connection failed", exception)
                        disconnect(owner, "The InfoView connection was lost. Reload InfoView to reconnect.")
                    }
                }
                ready.await()
                LeanInfoviewResponse.Success(project.service<InfoviewSettings>().configuration ?: JsonNull)
            }
            "clipboard" -> {
                val text = command.getValue("text").jsonPrimitive.content
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                LeanInfoviewResponse.Success()
            }
            "config" -> {
                project.service<InfoviewSettings>().update(command.getValue("config").jsonObject)
                LeanInfoviewResponse.Success()
            }
            "controls" -> {
                owner.controlsAvailable = command.getValue("enabled").jsonPrimitive.boolean
                LeanInfoviewResponse.Success()
            }
            else -> LeanInfoviewApi.getInstance().execute(
                project.projectId(), owner.id, json.decodeFromJsonElement<LeanInfoviewCommand>(command)
            )
        }
    }

    private fun reply(owner: Page, id: String, response: LeanInfoviewResponse) {
        if (page !== owner) return
        val fields = json.encodeToJsonElement<LeanInfoviewResponse>(response).jsonObject
        send(buildJsonObject { put("reply", JsonObject(fields + ("id" to JsonPrimitive(id)))) })
    }

    private fun send(message: JsonElement) {
        val token = JsonPrimitive(page.token)
        browser?.cefBrowser?.executeJavaScript(
            "if (window.leanPageToken === $token) { window.leanReceive?.($message); }", InfoviewResources.INDEX, 0
        )
    }

    fun requestAction(kind: InfoviewActionKind) {
        if (!canRequestAction) return
        send(buildJsonObject { put("action", buildJsonObject { put("kind", kind.wireName) }) })
    }

    fun reload() {
        if (!canReload) return
        page.controlsAvailable = false
        page.scope.cancel()
        errorPanel.isVisible = false
        val activeBrowser = browser
        if (activeBrowser == null) {
            page = Page()
            initializeBrowser()
        } else {
            activeBrowser.cefBrowser.reload()
        }
    }

    /** Losing the event stream ends this page's requests and RPC leases, even if the browser remains visible. */
    private fun disconnect(owner: Page, message: String) {
        if (page !== owner || !owner.scope.isActive) return
        owner.controlsAvailable = false
        owner.scope.cancel()
        send(buildJsonObject { put("closed", message) })
        showError(message)
    }

    private fun showError(message: String) {
        page.controlsAvailable = false
        error.text = message
        errorPanel.isVisible = true
    }

    override fun dispose() = scope.cancel()
}

private fun createInfoviewBrowser(lifetime: Disposable): JBCefBrowser? {
    if (!JBCefApp.isSupported()) return null
    val client = JBCefApp.getInstance().createClient()
    Disposer.register(lifetime, client)
    return JBCefBrowser.createBuilder().setClient(client).build()
}
