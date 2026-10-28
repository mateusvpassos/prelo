package prelo.intellij.json

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import prelo.intellij.ReportFiles
import prelo.intellij.fields.ClassFields
import prelo.intellij.fields.JsonText

/** Alt+Enter no arquivo: preenche "fields" a partir de "dataClass". */
class ImportFieldsIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "Prelo"
    override fun getText() = "Importar campos da classe (dataClass)"
    override fun startInWriteAction() = false

    // lê classes e mexe no documento: sem pré-visualização
    override fun generatePreview(project: Project, editor: Editor, file: com.intellij.psi.PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile as? JsonFile ?: return false
        val vf = file.virtualFile ?: file.originalFile.virtualFile ?: return false
        if (!ReportFiles.isReport(vf)) return false
        val dc = (file.topLevelValue as? JsonObject)?.findProperty("dataClass")?.value as? JsonStringLiteral ?: return false
        return dc.value.isNotBlank()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val file = element.containingFile as? JsonFile ?: return
        if (!FileModificationService.getInstance().prepareFileForWrite(file)) return
        val root = file.topLevelValue as? JsonObject ?: return
        val className = (root.findProperty("dataClass")?.value as? JsonStringLiteral)?.value ?: return
        val res = ProgressManager.getInstance().runProcessWithProgressSynchronously<ClassFields.Result, Exception>(
            { ClassFields.fields(project, className) }, "Lendo $className", true, project,
        )
        if (res.error != null) {
            Messages.showErrorDialog(project, res.error, "Prelo")
            return
        }
        replaceFields(project, file, res.fields)
    }

    companion object {
        private val MAPPER = ObjectMapper()

        @Suppress("UNCHECKED_CAST")
        fun replaceFields(project: Project, file: JsonFile, fromClass: List<Map<String, Any?>>) {
            val root = file.topLevelValue as? JsonObject ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val existing = root.findProperty("fields")?.value?.text?.let {
                runCatching { MAPPER.readValue(it, object : TypeReference<List<Map<String, Any?>>>() {}) }.getOrNull()
            }
            val merged = ClassFields.merge(fromClass, existing)
            val text = JsonText.pretty(merged, "  ")
            WriteCommandAction.writeCommandAction(project, file).withName("Importar campos da classe").run<RuntimeException> {
                val fieldsProp = root.findProperty("fields")
                if (fieldsProp?.value != null) {
                    val r = fieldsProp.value!!.textRange
                    doc.replaceString(r.startOffset, r.endOffset, text)
                } else {
                    val anchor = root.findProperty("dataClass")!!.textRange.endOffset
                    doc.insertString(anchor, ",\n  \"fields\": $text")
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }
}
