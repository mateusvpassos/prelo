package relato.intellij.fields

import com.fasterxml.jackson.databind.ObjectMapper

/** JSON no estilo dos templates: objetos curtos numa linha, listas longas quebradas. */
object JsonText {
    private val mapper = ObjectMapper()

    fun pretty(v: Any?, indent: String = "", width: Int = 110): String {
        if (v !is Map<*, *> && v !is List<*>) return mapper.writeValueAsString(v)
        val one = inline(v)
        if (indent.length + one.length <= width) return one
        val inner = "$indent  "
        return when (v) {
            is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { inner + pretty(it, inner, width) }
            is Map<*, *> -> v.entries.filter { it.value != null }
                .joinToString(",\n", "{\n", "\n$indent}") { inner + mapper.writeValueAsString(it.key.toString()) + ": " + pretty(it.value, inner, width) }
            else -> one
        }
    }

    fun inline(v: Any?): String = when (v) {
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(", ", "[ ", " ]") { inline(it) }
        is Map<*, *> -> {
            val es = v.entries.filter { it.value != null }
            if (es.isEmpty()) "{}" else es.joinToString(", ", "{ ", " }") { mapper.writeValueAsString(it.key.toString()) + ": " + inline(it.value) }
        }
        else -> mapper.writeValueAsString(v)
    }
}
