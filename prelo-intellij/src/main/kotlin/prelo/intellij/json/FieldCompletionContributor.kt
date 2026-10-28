package prelo.intellij.json

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.mateusvpassos.prelo.check.FieldCheck
import prelo.intellij.ReportFiles

/** Autocomplete de ${...}: campos visíveis no ponto (item da tabela/lista, objetos aninhados), variáveis e funções. */
class FieldCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().inside(JsonStringLiteral::class.java), Provider)
    }

    private object Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(params: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val vf = params.originalFile.virtualFile ?: return
            if (!ReportFiles.isReport(vf)) return
            val lit = PsiTreeUtil.getParentOfType(params.position, JsonStringLiteral::class.java, false) ?: return
            val path = JsonPaths.pathOf(lit)
            val prop = path.lastOrNull() as? String ?: return
            val caret = params.offset - lit.textRange.startOffset - 1
            if (caret < 0 || caret > lit.text.length - 1) return
            val before = lit.text.substring(1, 1 + caret)
            val expr = exprBeforeCaret(before, FieldCheck.isBareProperty(prop)) ?: return
            val root = try {
                FieldCheck.parse(params.originalFile.text)
            } catch (_: Exception) {
                return
            }
            val names = FieldCheck.complete(root, path, expr)
            val prefix = Regex("[\\w$]*$").find(expr)?.value ?: ""
            val rs = result.withPrefixMatcher(prefix)
            for ((i, n) in names.withIndex()) {
                val icon = when (n.kind()) {
                    "field" -> AllIcons.Nodes.Field
                    "function" -> AllIcons.Nodes.Function
                    "param" -> AllIcons.Nodes.Parameter
                    else -> AllIcons.Nodes.Variable
                }
                var el = LookupElementBuilder.create(n.name()).withIcon(icon).withTypeText(n.type())
                if (n.kind() == "function") el = el.withTailText("()", true)
                rs.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(el, (names.size - i).toDouble()))
            }
        }

        /** Texto da expressão até o cursor, ou null se o cursor não está numa expressão. */
        private fun exprBeforeCaret(before: String, bare: Boolean): String? {
            val open = before.lastIndexOf("\${")
            if (open >= 0 && before.indexOf('}', open) < 0) return before.substring(open + 2)
            return if (bare) before.substringAfterLast(',').trimStart('-', ' ') else null
        }
    }
}
