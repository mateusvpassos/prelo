package relato.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/** New → Relatório Relato: cria um *.report.json inicial (cabeçalho, tabela com total, rodapé com nº de página). */
class NewReportAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && targetDir(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = targetDir(e) ?: return
        val raw = Messages.showInputDialog(project, "Nome do relatório (ex.: vendas):", "Novo Relatório Relato", null) ?: return
        val name = raw.trim().removeSuffix(ReportFiles.SUFFIX).ifBlank { return }
        if (dir.findChild(name + ReportFiles.SUFFIX) != null) {
            Messages.showErrorDialog(project, "Já existe $name${ReportFiles.SUFFIX}", "Novo Relatório Relato")
            return
        }
        val file = WriteAction.compute<VirtualFile, Exception> {
            val f = dir.createChildData(this, name + ReportFiles.SUFFIX)
            VfsUtil.saveText(f, skeleton(name))
            f
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun targetDir(e: AnActionEvent): VirtualFile? {
        val f = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.getData(LangDataKeys.IDE_VIEW)?.orChooseDirectory?.virtualFile
        return if (f == null) null else if (f.isDirectory) f else f.parent
    }

    private fun skeleton(name: String) = """
        {
          "title": "${name.replaceFirstChar { it.uppercase() }}",
          "page": { "size": "A4", "orientation": "portrait", "margin": "22mm 15mm 18mm 15mm" },
          "parameters": [
            { "name": "empresa", "type": "string", "default": "Minha Empresa" }
          ],
          "fields": [
            { "name": "codigo", "type": "integer" },
            { "name": "descricao", "type": "string" },
            { "name": "data", "type": "date" },
            { "name": "valor", "type": "money" }
          ],
          "pageHeader": [
            { "type": "text", "value": "${'$'}{empresa}", "style": { "bold": true, "size": 12 } },
            { "type": "text", "value": "${name.replaceFirstChar { it.uppercase() }}", "style": { "color": "#555" } },
            { "type": "line", "color": "#333", "thickness": "1pt" }
          ],
          "body": [
            { "type": "table",
              "zebra": true,
              "columns": [
                { "header": "Código", "value": "${'$'}{codigo}", "width": "15%" },
                { "header": "Descrição", "value": "${'$'}{descricao}", "width": "45%" },
                { "header": "Data", "value": "${'$'}{data}", "format": "date", "width": "20%" },
                { "header": "Valor", "value": "${'$'}{valor}", "format": "currency", "width": "20%", "total": "sum" }
              ],
              "emptyText": "Nenhum registro."
            }
          ],
          "pageFooter": [
            { "type": "line" },
            { "type": "text", "value": "Página ${'$'}{page} de ${'$'}{pages}", "align": "right", "style": { "size": 8 } }
          ]
        }
    """.trimIndent() + "\n"
}
