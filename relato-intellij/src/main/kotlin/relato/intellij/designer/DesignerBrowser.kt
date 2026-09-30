package relato.intellij.designer

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/** Navegador do designer. Isolado para as classes JCEF só serem carregadas se existirem na IDE. */
interface DesignerBrowser : Disposable {
    val component: JComponent

    /** Entrega uma mensagem (objeto JSON) para {@code window.relato.receive}. */
    fun post(json: String)

    fun openDevtools()

    companion object {
        fun create(html: String, onMessage: (String) -> Unit): DesignerBrowser? = try {
            if (JBCefApp.isSupported()) JcefDesigner(html, onMessage) else null
        } catch (_: Throwable) {
            null
        }
    }
}

private class JcefDesigner(html: String, onMessage: (String) -> Unit) : DesignerBrowser {
    // pool de queries: permite criar o JSQuery mesmo se o navegador nativo já começou a ser criado
    private val client = JBCefApp.getInstance().createClient().apply {
        setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 4)
    }
    private val browser = JBCefBrowser.createBuilder().setClient(client).build()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        Disposer.register(this, query)
        Disposer.register(this, browser)
        Disposer.register(this, client)
        query.addHandler { msg ->
            onMessage(msg)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val js = "window.relatoHost = function(m) { ${query.inject("m")} }; if (window.relato) window.relato.hostReady();"
                b.executeJavaScript(js, b.url, 0)
            }
        }, browser.cefBrowser)
        browser.loadHTML(html)
    }

    override val component: JComponent get() = browser.component

    override fun post(json: String) {
        val safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        browser.cefBrowser.executeJavaScript("window.relato && window.relato.receive($safe);", browser.cefBrowser.url, 0)
    }

    override fun openDevtools() = browser.openDevtools()

    override fun dispose() {}
}
