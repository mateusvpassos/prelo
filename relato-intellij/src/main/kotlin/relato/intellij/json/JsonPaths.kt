package relato.intellij.json

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.psi.PsiElement

/** Converte entre caminhos do template (["body", 3, "value"]) e elementos PSI do JSON. */
object JsonPaths {
    fun find(file: JsonFile, path: List<Any>): JsonValue? {
        var cur: JsonValue? = file.topLevelValue
        for (tok in path) {
            cur = when (tok) {
                is String -> (cur as? JsonObject)?.findProperty(tok)?.value
                is Int -> (cur as? JsonArray)?.valueList?.getOrNull(tok)
                else -> null
            } ?: return null
        }
        return cur
    }

    fun pathOf(value: JsonValue): List<Any> {
        val out = ArrayDeque<Any>()
        var el: PsiElement = value
        while (true) {
            val parent = el.parent ?: break
            when (parent) {
                is JsonProperty -> {
                    if (parent.value != el) return emptyList()
                    out.addFirst(parent.name)
                    el = parent.parent ?: break
                }
                is JsonArray -> {
                    out.addFirst(parent.valueList.indexOf(el))
                    el = parent
                }
                else -> break
            }
        }
        return out.toList()
    }

    /** Posição no texto cru (com escapes) correspondente a um índice do valor decodificado. */
    fun rawOffset(raw: String, decodedIndex: Int): Int {
        var i = 0
        var d = 0
        while (i < raw.length && d < decodedIndex) {
            if (raw[i] == '\\' && i + 1 < raw.length) i += if (raw[i + 1] == 'u') 6 else 2 else i++
            d++
        }
        return i.coerceAtMost(raw.length)
    }
}
