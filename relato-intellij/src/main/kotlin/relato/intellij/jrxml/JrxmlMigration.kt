package relato.intellij.jrxml

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.mateusvpassos.relato.jrxml.JrxmlConverter
import io.github.mateusvpassos.relato.jrxml.JrxmlLinker
import io.github.mateusvpassos.relato.jrxml.SubreportHints
import relato.intellij.ReportFiles
import java.io.File

/** Converte .jrxml em .report.json (ao lado do original). */
object JrxmlMigration {
    class Outcome(val created: List<VirtualFile>, val warnings: Int, val failures: List<String>)

    fun jrxmlFiles(selection: Array<out VirtualFile>): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        for (f in selection) {
            if (f.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(f, { !it.name.equals("target", true) && !it.name.equals("build", true) }) {
                    if (!it.isDirectory && it.extension.equals("jrxml", true)) out += it
                    true
                }
            } else if (f.extension.equals("jrxml", true)) out += f
        }
        return out.distinct()
    }

    /** Parâmetro → sub-relatório, lendo o código Java das raízes do projeto. */
    fun hints(project: Project): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (root in ProjectRootManager.getInstance(project).contentRoots) out.putAll(SubreportHints.scan(File(root.path).toPath()))
        return out
    }

    /** Converte e grava; roda fora da EDT (a escrita volta para a EDT). */
    fun convert(project: Project, files: List<VirtualFile>, hints: Map<String, String>): Outcome {
        val known = files.map { it.nameWithoutExtension }.toSet()
        val opts = JrxmlConverter.Options(hints, known)
        val converted = LinkedHashMap<String, String>()
        val dirOf = HashMap<String, VirtualFile>()
        val failures = mutableListOf<String>()
        var warnings = 0
        for (f in files) {
            try {
                val r = JrxmlConverter.convert(f.contentsToByteArray(), f.nameWithoutExtension, opts)
                converted[f.nameWithoutExtension] = r.json()
                dirOf[f.nameWithoutExtension] = f.parent
                warnings += r.warnings().size
            } catch (e: Exception) {
                failures += "${f.name}: ${e.message}"
            }
        }
        val linked = JrxmlLinker.link(converted)
        val created = mutableListOf<VirtualFile>()
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<Exception> {
                for ((name, json) in linked) {
                    val dir = dirOf[name] ?: continue
                    val target = dir.findChild(name + ReportFiles.SUFFIX) ?: dir.createChildData(this, name + ReportFiles.SUFFIX)
                    VfsUtil.saveText(target, json)
                    created += target
                }
            }
        }
        return Outcome(created, warnings, failures)
    }
}

class ConvertJrxmlAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val sel = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null &&
            sel.any { it.isDirectory || it.extension.equals("jrxml", true) } &&
            (sel.any { it.extension.equals("jrxml", true) } || sel.size == 1)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = JrxmlMigration.jrxmlFiles(e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty())
        if (files.isEmpty()) {
            Messages.showInfoMessage(project, "Nenhum .jrxml na seleção.", "Relato")
            return
        }
        val existing = files.count { it.parent?.findChild(it.nameWithoutExtension + ReportFiles.SUFFIX) != null }
        if (existing > 0 && Messages.showYesNoDialog(project, "$existing arquivo(s) .report.json já existem e serão substituídos. Continuar?",
                "Converter para Relato", null) != Messages.YES) return

        object : Task.Backgroundable(project, "Convertendo ${files.size} relatório(s) Jasper", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Lendo o código Java (sub-relatórios)…"
                val hints = JrxmlMigration.hints(project)
                indicator.text = "Convertendo…"
                val r = JrxmlMigration.convert(project, files, hints)
                val msg = buildString {
                    append("${r.created.size} relatório(s) convertido(s)")
                    if (r.warnings > 0) append(", ${r.warnings} ponto(s) para revisar (comentários no topo de cada arquivo)")
                    if (r.failures.isNotEmpty()) append("<br>Falhas:<br>" + r.failures.joinToString("<br>"))
                }
                NotificationGroupManager.getInstance().getNotificationGroup("Relato")
                    .createNotification("Migração do Jasper", msg, if (r.failures.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
                    .notify(project)
                if (r.created.size in 1..3) {
                    ApplicationManager.getApplication().invokeLater {
                        r.created.forEach { FileEditorManager.getInstance(project).openFile(it, true) }
                    }
                }
            }
        }.queue()
    }
}
