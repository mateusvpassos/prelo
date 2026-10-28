package prelo.intellij.json

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.mateusvpassos.prelo.check.FieldCheck
import prelo.intellij.ReportFiles

/** Aviso em ${campo} que não existe nos fields (respeitando o escopo de tabelas e listas). */
class FieldCheckAnnotator : ExternalAnnotator<String, List<FieldCheck.Problem>>() {
    override fun collectInformation(file: PsiFile): String? {
        val vf = file.virtualFile ?: file.originalFile.virtualFile
        return if (file is JsonFile && vf != null && ReportFiles.isReport(vf)) file.text else null
    }

    override fun doAnnotate(text: String?): List<FieldCheck.Problem> = if (text == null) emptyList() else FieldCheck.check(text)

    override fun apply(file: PsiFile, problems: List<FieldCheck.Problem>, holder: AnnotationHolder) {
        val json = file as? JsonFile ?: return
        for (p in problems) {
            val lit = JsonPaths.find(json, p.path()) as? JsonStringLiteral ?: continue
            val r = lit.textRange
            val range = if (p.start() >= 0 && r.length >= 2) {
                val raw = lit.text.substring(1, lit.text.length - 1)
                val s = r.startOffset + 1 + JsonPaths.rawOffset(raw, p.start())
                val e = r.startOffset + 1 + JsonPaths.rawOffset(raw, p.end())
                TextRange(s, maxOf(e, s + 1).coerceAtMost(r.endOffset))
            } else {
                r
            }
            holder.newAnnotation(HighlightSeverity.WARNING, p.message()).range(range).create()
        }
    }
}
