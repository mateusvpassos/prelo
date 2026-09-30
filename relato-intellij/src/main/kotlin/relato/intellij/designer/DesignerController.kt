package relato.intellij.designer

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import relato.intellij.PreviewEngine
import relato.intellij.PreviewInputs
import relato.intellij.fields.ClassFields
import io.github.mateusvpassos.relato.check.FieldCheck
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Ponte entre o designer (HTML/JS) e o documento do *.report.json.
 * O designer edita o modelo e devolve o JSON inteiro; aqui ele vira uma edição mínima no documento
 * (com desfazer da IDE). Edições feitas no texto voltam para o designer.
 */
class DesignerController(
    private val project: Project,
    private val file: VirtualFile,
    private val send: (String) -> Unit,
) : Disposable {
    private val log = logger<DesignerController>()
    private val document: Document = FileDocumentManager.getInstance().getDocument(file)
        ?: error("sem documento para ${file.path}")
    private val loadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val previewAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile private var ready = false
    private var applying = false
    private var schemaSent = false

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (applying || !ready) return
                loadAlarm.cancelAllRequests()
                loadAlarm.addRequest({ sendLoad() }, 300)
            }
        }, this)
    }

    /** Mensagem vinda do designer (qualquer thread). */
    fun onMessage(raw: String) {
        val msg = try {
            MAPPER.readTree(raw)
        } catch (e: Exception) {
            log.warn("mensagem inválida do designer: $raw", e)
            return
        }
        when (msg.path("type").asText()) {
            "ready" -> ApplicationManager.getApplication().invokeLater({
                ready = true
                schemaSent = false
                post(mapOf("type" to "theme", "dark" to !JBColor.isBright()))
                sendLoad()
            }, { project.isDisposed })
            "change" -> {
                val json = msg.path("json").asText()
                ApplicationManager.getApplication().invokeLater({ apply(json) }, { project.isDisposed })
            }
            "preview" -> {
                previewAlarm.cancelAllRequests()
                previewAlarm.addRequest({ renderPreview() }, 150)
            }
            "findClasses" -> {
                val q = msg.path("query").asText()
                ApplicationManager.getApplication().executeOnPooledThread {
                    post(mapOf("type" to "classes", "query" to q, "items" to ClassFields.findClasses(project, q)))
                }
            }
            "importFields" -> {
                val cls = msg.path("className").asText()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val r = ClassFields.fields(project, cls)
                    post(mapOf("type" to "fields", "className" to cls, "fields" to r.fields, "error" to r.error))
                }
            }
        }
    }

    fun sendLoad() {
        val text = runReadAction { document.text }
        val normalized = try {
            MAPPER.writeValueAsString(MAPPER.readTree(text) ?: MAPPER.createObjectNode())
        } catch (e: Exception) {
            post(mapOf("type" to "invalid", "message" to (e.message ?: e.toString()).substringBefore(" at [Source")))
            return
        }
        val msg = mutableMapOf<String, Any>("type" to "load", "json" to normalized, "fileName" to file.name)
        if (!schemaSent) {
            msg["schema"] = SCHEMA
            schemaSent = true
        }
        post(msg)
        sendProblems(normalized)
    }

    /** Avisos de campos (FieldCheck) para o designer marcar os blocos. */
    private fun sendProblems(json: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = FieldCheck.check(json).map { mapOf("path" to it.path(), "message" to it.message()) }
            post(mapOf("type" to "problems", "items" to items))
        }
    }

    private fun apply(json: String) {
        if (runReadAction { document.text } == json) return
        WriteCommandAction.runWriteCommandAction(project, "Relato Designer", "relato.designer", {
            applying = true
            try {
                replaceMinimal(document, json)
            } finally {
                applying = false
            }
        })
        sendProblems(json)
    }

    private fun renderPreview() {
        val input = try {
            PreviewInputs.of(project, file)
        } catch (e: Exception) {
            post(mapOf("type" to "preview", "error" to PreviewEngine.describe(e)))
            return
        }
        val res = PreviewEngine.render(input, dpi = 80f)
        val pages = res.pages.map { img ->
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "png", out)
            "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
        }
        post(mapOf("type" to "preview", "pages" to pages, "total" to res.totalPages, "notes" to res.notes, "error" to res.error))
    }

    private fun post(msg: Map<String, Any?>) {
        send(MAPPER.writeValueAsString(msg))
    }

    override fun dispose() {}

    companion object {
        internal val MAPPER: ObjectMapper = ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)

        private val SCHEMA: String by lazy {
            DesignerController::class.java.getResourceAsStream("/relato.schema.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        }

        /** Troca só o trecho que mudou, preservando cursor e deixando o desfazer enxuto. */
        fun replaceMinimal(doc: Document, newText: String) {
            val old = doc.charsSequence
            var start = 0
            val max = minOf(old.length, newText.length)
            while (start < max && old[start] == newText[start]) start++
            var endOld = old.length
            var endNew = newText.length
            while (endOld > start && endNew > start && old[endOld - 1] == newText[endNew - 1]) {
                endOld--
                endNew--
            }
            doc.replaceString(start, endOld, newText.substring(start, endNew))
        }
    }
}
