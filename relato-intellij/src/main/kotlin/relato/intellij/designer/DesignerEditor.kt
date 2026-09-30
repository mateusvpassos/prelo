package relato.intellij.designer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import relato.intellij.ReportFiles
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Aba "Designer" do *.report.json: editor visual de arrastar e soltar. */
class DesignerEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = ReportFiles.isReport(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DesignerEditor(project, file)

    override fun getEditorTypeId(): String = "relato-designer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

class DesignerEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    private var browser: DesignerBrowser? = null
    internal val controller = DesignerController(project, file) { json -> browser?.post(json) }

    init {
        Disposer.register(this, controller)
        val b = DesignerBrowser.create(DesignerHtml.page(), controller::onMessage)
        if (b != null) {
            browser = b
            Disposer.register(this, b)
            panel.add(b.component, BorderLayout.CENTER)
        } else {
            panel.add(JBLabel("O designer precisa do navegador embutido (JCEF), indisponível nesta IDE.", SwingConstants.CENTER).apply {
                border = JBUI.Borders.empty(24)
            }, BorderLayout.CENTER)
        }
    }

    fun openDevtools() = browser?.openDevtools()

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component ?: panel
    override fun getName(): String = "Designer"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile(): VirtualFile = file
    override fun dispose() {}
}

/** HTML do designer com CSS e JS embutidos (carregado via loadHTML, sem servidor). */
object DesignerHtml {
    fun page(): String {
        val html = res("designer.html")
        return html
            .replace("<link rel=\"stylesheet\" href=\"designer.css\">", "<style>\n${res("designer.css")}\n</style>")
            .replace("<script src=\"designer.js\"></script>", "<script>\n${res("designer.js")}\n</script>")
    }

    private fun res(name: String): String =
        DesignerHtml::class.java.getResourceAsStream("/designer/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
}
