package prelo.intellij

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.mateusvpassos.prelo.Format
import io.github.mateusvpassos.prelo.SampleData
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

class ReportPanel(private val project: Project, private val file: VirtualFile) : JPanel(BorderLayout()), Disposable {
    private val log = logger<ReportPanel>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val seq = AtomicInteger()
    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private val pagesView = PagesView()
    private val scroll = JBScrollPane(pagesView)
    private val errorArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(12) }
    private val cards = JPanel(CardLayout())

    @Volatile internal var lastResult: PreviewEngine.Result? = null

    init {
        scroll.verticalScrollBar.unitIncrement = 24
        cards.add(scroll, "pages")
        cards.add(JBScrollPane(errorArea), "error")
        add(createToolbar(), BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = pagesView.relayout(scroll.viewport.width)
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val f = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (f == file || isSample(f)) schedule(DEBOUNCE)
                }
            },
            this,
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { e -> e.file?.let { it == file || isSample(it) } == true || e.path.endsWith(ReportFiles.SAMPLE_SUFFIX) }) {
                        schedule(DEBOUNCE)
                    }
                }
            },
        )
        status.text = "Renderizando…"
        schedule(0)
    }

    private fun isSample(f: VirtualFile) = f.parent == file.parent && f.name == ReportFiles.baseName(file) + ReportFiles.SAMPLE_SUFFIX

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Atualizar", "Renderizar de novo", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = schedule(0)
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Diminuir", null, AllIcons.General.ZoomOut) {
            override fun actionPerformed(e: AnActionEvent) = pagesView.zoomBy(1 / 1.25f)
        })
        group.add(object : DumbAwareAction("Aumentar", null, AllIcons.General.ZoomIn) {
            override fun actionPerformed(e: AnActionEvent) = pagesView.zoomBy(1.25f)
        })
        group.add(object : ToggleAction("Ajustar à largura", null, AllIcons.General.FitContent) {
            override fun isSelected(e: AnActionEvent) = pagesView.fitWidth
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                pagesView.fitWidth = state
                pagesView.relayout(scroll.viewport.width)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Dados de exemplo", "Abre (ou cria a partir de 'fields') o arquivo .sample.json", AllIcons.FileTypes.Json) {
            override fun actionPerformed(e: AnActionEvent) = openOrCreateSample()
        })
        group.add(object : DumbAwareAction("Exportar PDF", null, AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) = export(Format.PDF)
        })
        group.add(object : DumbAwareAction("Exportar XLSX", null, AllIcons.FileTypes.Any_type) {
            override fun actionPerformed(e: AnActionEvent) = export(Format.XLSX)
        })
        group.add(object : DumbAwareAction("Exportar HTML", null, AllIcons.FileTypes.Html) {
            override fun actionPerformed(e: AnActionEvent) = export(Format.HTML)
        })
        group.add(object : DumbAwareAction("Exportar CSV", "Primeira tabela do relatório, separada por ';'", AllIcons.FileTypes.Text) {
            override fun actionPerformed(e: AnActionEvent) = export(Format.CSV)
        })
        val tb = ActionManager.getInstance().createActionToolbar("PreloPreview", group, true)
        tb.targetComponent = this
        return tb.component
    }

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({ render() }, delay)
    }

    private fun input(): PreviewEngine.Input = PreviewInputs.of(project, file)

    private fun render() {
        val mySeq = seq.incrementAndGet()
        try {
            val res = PreviewEngine.render(input())
            if (mySeq != seq.get()) return
            lastResult = res
            ApplicationManager.getApplication().invokeLater({ show(res) }, { project.isDisposed })
        } catch (e: Throwable) {
            log.warn("Falha no preview de ${file.path}", e)
            ApplicationManager.getApplication().invokeLater({
                errorArea.text = "Erro ao gerar o preview:\n\n${PreviewEngine.describe(e)}"
                (cards.layout as CardLayout).show(cards, "error")
            }, { project.isDisposed })
        }
    }

    private fun show(res: PreviewEngine.Result) {
        val layout = cards.layout as CardLayout
        if (res.error != null || res.pages.isEmpty()) {
            errorArea.text = (res.error ?: "O relatório não gerou páginas.") +
                if (res.notes.isEmpty()) "" else "\n\n" + res.notes.joinToString("\n") { "• $it" }
            layout.show(cards, "error")
            status.text = "Erro no relatório"
            return
        }
        pagesView.setPages(res.pages, scroll.viewport.width)
        layout.show(cards, "pages")
        val shown = if (res.pages.size < res.totalPages) " (mostrando ${res.pages.size})" else ""
        val source = if (runReadAction { ReportFiles.sampleOf(file) } != null) "dados de ${ReportFiles.baseName(file)}${ReportFiles.SAMPLE_SUFFIX}" else "dados inventados"
        status.text = "${res.totalPages} página(s)$shown  ·  $source" + if (res.notes.isEmpty()) "" else "  ·  ${res.notes.size} aviso(s)"
        status.toolTipText = if (res.notes.isEmpty()) null else "<html>" + res.notes.joinToString("<br>") { it.replace("<", "&lt;") } + "</html>"
    }

    private fun openOrCreateSample() {
        val existing = ReportFiles.sampleOf(file)
        if (existing != null) {
            FileEditorManager.getInstance(project).openFile(existing, true)
            return
        }
        val res = lastResult
        val template = res?.template ?: return notify("Corrija o template antes de criar os dados de exemplo.", NotificationType.WARNING)
        val json = SampleData.generate(template, 10).toJson()
        val dir = file.parent ?: return
        val created = WriteAction.compute<VirtualFile, Exception> {
            val f = dir.createChildData(this, ReportFiles.baseName(file) + ReportFiles.SAMPLE_SUFFIX)
            VfsUtil.saveText(f, json)
            f
        }
        FileEditorManager.getInstance(project).openFile(created, true)
    }

    private fun export(format: Format) {
        val res = lastResult
        val template = res?.template
        val sample = res?.sample
        if (template == null || sample == null || res.error != null) {
            notify("O relatório tem erros; corrija antes de exportar.", NotificationType.WARNING)
            return
        }
        val descriptor = FileSaverDescriptor("Exportar ${format.name}", "", format.extension())
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, ReportFiles.baseName(file) + "." + format.extension()) ?: return
        val input = input()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val bytes = PreviewEngine.produce(template, sample, input, format)
                wrapper.file.writeBytes(bytes)
                VfsUtil.markDirtyAndRefresh(true, false, false, wrapper.file)
                notify("Exportado: ${wrapper.file.absolutePath}", NotificationType.INFORMATION)
            } catch (e: Exception) {
                notify("Falha ao exportar: ${PreviewEngine.describe(e)}", NotificationType.ERROR)
            }
        }
    }

    private fun notify(msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Prelo").createNotification(msg, type).notify(project)
    }

    override fun dispose() {}

    companion object {
        private const val DEBOUNCE = 500
    }
}

/** Páginas empilhadas, estilo leitor de PDF. */
private class PagesView : JPanel() {
    private var pages: List<BufferedImage> = emptyList()
    var fitWidth = true
    private var zoom = 1f
    private var viewportWidth = 800

    init {
        background = JBColor(Color(0xE4, 0xE4, 0xE4), Color(0x2B, 0x2D, 0x30))
    }

    fun setPages(p: List<BufferedImage>, width: Int) {
        pages = p
        relayout(width)
    }

    fun zoomBy(f: Float) {
        zoom = currentScale() * f
        fitWidth = false
        relayout(viewportWidth)
    }

    fun relayout(width: Int) {
        if (width > 0) viewportWidth = width
        revalidate()
        repaint()
    }

    private fun currentScale(): Float {
        val w = pages.maxOfOrNull { it.width } ?: return 1f
        return if (fitWidth) ((viewportWidth - 2 * PAD).toFloat() / w).coerceIn(0.1f, 4f) else zoom
    }

    override fun getPreferredSize(): Dimension {
        val s = currentScale()
        val w = (pages.maxOfOrNull { it.width } ?: 0) * s + 2 * PAD
        val h = pages.sumOf { (it.height * s).toDouble() + PAD } + PAD
        return Dimension(w.toInt(), h.toInt())
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val s = currentScale()
        var y = PAD
        for (img in pages) {
            val w = (img.width * s).toInt()
            val h = (img.height * s).toInt()
            val x = ((width - w) / 2).coerceAtLeast(PAD)
            g2.color = Color(0, 0, 0, 40)
            g2.fillRect(x + 3, y + 3, w, h)
            g2.drawImage(img, x, y, w, h, null)
            y += h + PAD
        }
    }

    companion object {
        private const val PAD = 16
    }
}
