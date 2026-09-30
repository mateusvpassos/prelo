package relato.intellij.fields

import com.intellij.openapi.project.Project
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil

/**
 * Lê as propriedades de uma classe do projeto (record, getters, Lombok, Kotlin) e devolve no formato de
 * {@code fields} do template. Só é carregada quando o plugin Java existe (ver [ClassFields]).
 */
internal object JavaClassFields {
    private const val MAX_DEPTH = 4

    private val MONEY_NAME = Regex("(?i)valor|preco|preço|total|custo|salario|salário|price|amount|saldo|desconto|frete|imposto")
    private val STRING = setOf("java.lang.String", "java.lang.CharSequence", "java.lang.Character", "java.util.UUID", "java.time.LocalTime")
    private val INTEGER = setOf("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger",
        "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong", "kotlin.Int", "kotlin.Long")
    private val DECIMAL = setOf("java.math.BigDecimal", "java.lang.Double", "java.lang.Float", "java.lang.Number")
    private val DATE = setOf("java.time.LocalDate", "java.sql.Date")
    private val DATETIME = setOf("java.time.LocalDateTime", "java.util.Date", "java.sql.Timestamp", "java.time.Instant",
        "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.util.Calendar")

    private val KNOWN_SIMPLE = (STRING + INTEGER + DECIMAL + DATE + DATETIME + setOf("java.lang.Boolean", "java.util.List", "java.util.Set", "java.util.Collection"))
        .associateBy { it.substringAfterLast('.') }

    fun findClass(project: Project, name: String): PsiClass? {
        val fqn = name.trim().replace('$', '.')
        return JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
    }

    fun fields(project: Project, className: String): List<Map<String, Any?>> {
        val cls = findClass(project, className) ?: throw IllegalArgumentException("classe não encontrada: $className")
        return propertiesOf(cls, 0, mutableSetOf())
    }

    /** Classes do projeto (não bibliotecas) cujo nome contém o texto; FQN, até [limit]. */
    fun findClasses(project: Project, query: String, limit: Int = 30): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val shortQ = q.substringAfterLast('.').lowercase()
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val out = sortedSetOf<String>(compareBy({ !it.substringAfterLast('.').lowercase().startsWith(shortQ) }, { it }))
        for (short in cache.allClassNames) {
            if (!short.lowercase().contains(shortQ)) continue
            for (c in cache.getClassesByName(short, scope)) {
                val fqn = c.qualifiedName ?: continue
                if (q.contains('.') && !fqn.lowercase().contains(q.lowercase())) continue
                out += fqn
            }
            if (out.size >= limit * 3) break
        }
        return out.take(limit)
    }

    private fun propertiesOf(cls: PsiClass, depth: Int, visiting: MutableSet<String>): List<Map<String, Any?>> {
        val qn = cls.qualifiedName ?: cls.name ?: "?"
        if (!visiting.add(qn)) return emptyList()
        try {
            val props = LinkedHashMap<String, PsiType>()
            if (cls.isRecord) {
                for (rc in cls.recordComponents) props[rc.name] = rc.type
            } else {
                val getters = LinkedHashMap<String, PsiType>()
                for (m in cls.allMethods) {
                    if (m.isConstructor || m.hasModifierProperty(PsiModifier.STATIC) || !m.hasModifierProperty(PsiModifier.PUBLIC)) continue
                    if (m.parameterList.parametersCount != 0) continue
                    val rt = m.returnType ?: continue
                    if (rt == PsiTypes.voidType()) continue
                    if (m.containingClass?.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) continue
                    val n = m.name
                    val prop = when {
                        n.startsWith("get") && n.length > 3 -> decap(n.substring(3))
                        n.startsWith("is") && n.length > 2 && (rt == PsiTypes.booleanType() || rt.canonicalText == "java.lang.Boolean") -> decap(n.substring(2))
                        else -> null
                    } ?: continue
                    getters.putIfAbsent(prop, rt)
                }
                val fields = cls.allFields.filter { !it.hasModifierProperty(PsiModifier.STATIC) && it.containingClass?.qualifiedName != CommonClassNames.JAVA_LANG_OBJECT }
                // ordem de declaração dos campos; getters sem campo vão no fim
                for (f in fields) getters[f.name]?.let { props[f.name] = it }
                for ((k, v) in getters) props.putIfAbsent(k, v)
                for (f in fields) if (f.hasModifierProperty(PsiModifier.PUBLIC)) props.putIfAbsent(f.name, f.type)
                // Lombok sem o plugin (getters invisíveis): usa os campos
                if (props.isEmpty()) for (f in fields) props[f.name] = f.type
            }
            return props.map { (name, type) -> field(name, type, depth, visiting) }
        } finally {
            visiting.remove(qn)
        }
    }

    private fun field(name: String, type: PsiType, depth: Int, visiting: MutableSet<String>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["name"] = name
        when (type) {
            is PsiPrimitiveType -> out["type"] = when (type) {
                PsiTypes.booleanType() -> "boolean"
                PsiTypes.doubleType(), PsiTypes.floatType() -> if (MONEY_NAME.containsMatchIn(name)) "money" else "decimal"
                PsiTypes.charType() -> "string"
                else -> "integer"
            }
            is PsiArrayType -> {
                val comp = type.componentType
                if (comp == PsiTypes.byteType()) out["type"] = "string"
                else asList(out, name, comp, depth, visiting)
            }
            is PsiClassType -> classType(out, name, type, depth, visiting)
            else -> out["type"] = "string"
        }
        return out
    }

    private fun classType(out: MutableMap<String, Any?>, name: String, type: PsiClassType, depth: Int, visiting: MutableSet<String>) {
        val cls = type.resolve()
        val raw = cls?.qualifiedName ?: type.canonicalText.substringBefore('<')
        // sem JDK resolvido (projeto mal configurado), reconhece pelo nome simples
        val qn = if (cls == null && '.' !in raw) KNOWN_SIMPLE[raw] ?: raw else raw
        when {
            qn in STRING -> out["type"] = "string"
            qn in INTEGER -> out["type"] = "integer"
            qn in DECIMAL -> out["type"] = if (MONEY_NAME.containsMatchIn(name)) "money" else "decimal"
            qn == "java.lang.Boolean" -> out["type"] = "boolean"
            qn in DATE -> out["type"] = "date"
            qn in DATETIME -> out["type"] = "datetime"
            cls == null -> out["type"] = "string"
            cls.isEnum -> out["type"] = "string"
            qn == "java.util.Optional" -> {
                val inner = type.parameters.firstOrNull()
                if (inner == null) out["type"] = "string" else out.putAll(field(name, inner, depth, visiting) - "name")
            }
            InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_LANG_ITERABLE) || qn == CommonClassNames.JAVA_LANG_ITERABLE -> {
                val elem = PsiUtil.substituteTypeParameter(type, CommonClassNames.JAVA_LANG_ITERABLE, 0, false)
                if (elem == null) out["type"] = "list" else asList(out, name, elem, depth, visiting)
            }
            InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_UTIL_MAP) || qn == CommonClassNames.JAVA_UTIL_MAP -> out["type"] = "object"
            qn.startsWith("java.") || qn.startsWith("javax.") || qn.startsWith("kotlin.") -> out["type"] = "string"
            depth >= MAX_DEPTH || qn in visiting -> out["type"] = "object"
            else -> {
                out["type"] = "object"
                val sub = propertiesOf(cls, depth + 1, visiting)
                if (sub.isNotEmpty()) out["fields"] = sub
            }
        }
    }

    private fun asList(out: MutableMap<String, Any?>, name: String, elem: PsiType, depth: Int, visiting: MutableSet<String>) {
        out["type"] = "list"
        val e = field(name, elem, depth, visiting)
        @Suppress("UNCHECKED_CAST")
        val sub = e["fields"] as? List<Map<String, Any?>>
        if (e["type"] == "object" && sub != null) out["fields"] = sub
    }

    private fun decap(s: String): String =
        if (s.length > 1 && s[0].isUpperCase() && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercase() }
}
