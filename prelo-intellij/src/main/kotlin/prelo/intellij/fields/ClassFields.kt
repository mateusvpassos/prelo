package prelo.intellij.fields

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/** Fachada segura: funciona sem o plugin Java (devolve erro amigável). Chame fora da EDT. */
object ClassFields {
    class Result(val fields: List<Map<String, Any?>>, val error: String?)

    fun fields(project: Project, className: String): Result {
        if (className.isBlank()) return Result(emptyList(), "informe a classe dos dados")
        if (DumbService.isDumb(project)) return Result(emptyList(), "a IDE ainda está indexando; tente de novo em instantes")
        return try {
            Result(ReadAction.compute<List<Map<String, Any?>>, Exception> { JavaClassFields.fields(project, className) }, null)
        } catch (e: IllegalArgumentException) {
            Result(emptyList(), e.message)
        } catch (e: NoClassDefFoundError) {
            Result(emptyList(), "suporte a Java indisponível nesta IDE")
        }
    }

    fun findClasses(project: Project, query: String): List<String> {
        if (DumbService.isDumb(project)) return emptyList()
        return try {
            ReadAction.compute<List<String>, Exception> { JavaClassFields.findClasses(project, query) }
        } catch (e: NoClassDefFoundError) {
            emptyList()
        }
    }

    /**
     * Junta os campos lidos da classe com os já declarados: vale a estrutura da classe, mas mantém
     * label e sample que o usuário tinha escrito.
     */
    @Suppress("UNCHECKED_CAST")
    fun merge(fromClass: List<Map<String, Any?>>, existing: List<Map<String, Any?>>?): List<Map<String, Any?>> {
        val old = existing.orEmpty().associateBy { it["name"] as? String }
        return fromClass.map { f ->
            val prev = old[f["name"] as? String] ?: return@map f
            val out = LinkedHashMap<String, Any?>()
            out["name"] = f["name"]
            out["type"] = f["type"]
            prev["label"]?.let { out["label"] = it }
            prev["sample"]?.let { out["sample"] = it }
            (f["fields"] as? List<Map<String, Any?>>)?.let { out["fields"] = merge(it, prev["fields"] as? List<Map<String, Any?>>) }
            out
        }
    }
}
