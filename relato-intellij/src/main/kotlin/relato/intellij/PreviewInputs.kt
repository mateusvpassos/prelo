package relato.intellij

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Monta a entrada do preview a partir do estado atual da IDE (inclusive texto não salvo). */
object PreviewInputs {
    fun of(project: Project, file: VirtualFile): PreviewEngine.Input {
        val sample = runReadAction { ReportFiles.sampleOf(file) }?.let { text(it) }
        val roots = runReadAction { ProjectRootManager.getInstance(project).contentSourceRoots.map { File(it.path) } }
        return PreviewEngine.Input(text(file), sample, file.parent?.let { File(it.path) }, roots)
    }

    fun text(f: VirtualFile): String = runReadAction {
        FileDocumentManager.getInstance().getCachedDocument(f)?.text ?: VfsUtil.loadText(f)
    }
}
