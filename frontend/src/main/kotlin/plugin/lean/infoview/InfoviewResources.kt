package plugin.lean.infoview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.ui.jcef.utils.JBCefStreamResourceHandler
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI

/** Browser-local resources: no filesystem URLs, listening port, or runtime CDN dependency. */
internal class InfoviewResources(private val parent: Disposable) : CefRequestHandlerAdapter() {
    override fun onBeforeBrowse(
        browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean
    ): Boolean {
        if (!frame.isMain || request.url == INDEX) return false
        openExternal(request.url)
        return true
    }

    override fun onOpenURLFromTab(
        browser: CefBrowser, frame: CefFrame, targetUrl: String, userGesture: Boolean
    ): Boolean {
        openExternal(targetUrl)
        return true
    }

    override fun getResourceRequestHandler(
        browser: CefBrowser, frame: CefFrame, request: CefRequest,
        isNavigation: Boolean, isDownload: Boolean, requestInitiator: String, disableDefaultHandling: BoolRef
    ): CefResourceRequestHandlerAdapter? {
        val uri = URI(request.url)
        if (uri.scheme != "https" || uri.rawAuthority != HOST) return null
        disableDefaultHandling.set(true)
        return object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(browser: CefBrowser, frame: CefFrame, request: CefRequest) = resource(uri)
        }
    }

    private fun resource(uri: URI): CefResourceHandler {
        val path = uri.path.removePrefix("/")
        val allowed = path.split('/').none { it == "." || it == ".." } && '\\' !in path && '\u0000' !in path
        val bytes = if (allowed) javaClass.getResourceAsStream("/lean/infoview/$path")?.use { it.readBytes() } else null
        val body = bytes ?: "Resource not found".toByteArray()
        val contentType = when (path.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "ttf" -> "font/ttf"
            else -> "text/plain"
        }
        // The platform handler supports both in-process and out-of-process JCEF resource callbacks.
        return object : JBCefStreamResourceHandler(body.inputStream(), contentType, parent, mapOf(
            "Cache-Control" to "no-cache",
            "X-Content-Type-Options" to "nosniff"
        )) {
            override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
                super.getResponseHeaders(response, responseLength, redirectUrl)
                response.status = if (bytes == null) 404 else 200
                responseLength.set(body.size)
            }
        }
    }

    private fun openExternal(url: String) {
        if (URI(url).scheme in setOf("https", "http", "mailto")) BrowserUtil.browse(url)
    }

    companion object {
        private const val HOST = "lean-infoview.invalid"
        const val INDEX = "https://$HOST/index.html"
    }
}
