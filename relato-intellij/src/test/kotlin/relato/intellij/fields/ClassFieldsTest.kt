package relato.intellij.fields

import com.intellij.json.psi.JsonFile
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import relato.intellij.json.ImportFieldsIntention

class ClassFieldsTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = JAVA_LATEST_WITH_LATEST_JDK

    private fun addDomain() {
        myFixture.addClass("package demo; public record Cliente(String nome, String cnpj, Endereco endereco) {}")
        myFixture.addClass("package demo; public record Endereco(String cidade, String uf) {}")
        myFixture.addClass(
            """
            package demo;
            import java.math.BigDecimal;
            import java.time.LocalDate;
            import java.util.List;
            public class Venda {
                private Long id;
                private Cliente cliente;
                private LocalDate data;
                private BigDecimal valor;
                private double peso;
                private boolean pago;
                private Status status;
                private List<Item> itens;
                private List<String> tags;
                private Venda origem;
                public Long getId() { return id; }
                public Cliente getCliente() { return cliente; }
                public LocalDate getData() { return data; }
                public BigDecimal getValor() { return valor; }
                public double getPeso() { return peso; }
                public boolean isPago() { return pago; }
                public Status getStatus() { return status; }
                public List<Item> getItens() { return itens; }
                public List<String> getTags() { return tags; }
                public Venda getOrigem() { return origem; }
                public String getResumo() { return ""; }
                public static Venda nova() { return null; }
                public enum Status { ABERTA, PAGA }
                public record Item(String produto, int qtd, BigDecimal preco) {}
            }
            """.trimIndent(),
        )
    }

    fun testReadsRecordsGettersNestedAndLists() {
        addDomain()
        val r = ClassFields.fields(project, "demo.Venda")
        assertNull(r.error)
        val byName = r.fields.associateBy { it["name"] }
        assertEquals(listOf("id", "cliente", "data", "valor", "peso", "pago", "status", "itens", "tags", "origem", "resumo"), r.fields.map { it["name"] })
        assertEquals("integer", byName["id"]!!["type"])
        assertEquals("date", byName["data"]!!["type"])
        assertEquals("money", byName["valor"]!!["type"])
        assertEquals("decimal", byName["peso"]!!["type"])
        assertEquals("boolean", byName["pago"]!!["type"])
        assertEquals("string", byName["status"]!!["type"])
        assertEquals("list", byName["tags"]!!["type"])
        assertNull(byName["tags"]!!["fields"])
        @Suppress("UNCHECKED_CAST")
        val cliente = byName["cliente"]!!["fields"] as List<Map<String, Any?>>
        assertEquals(listOf("nome", "cnpj", "endereco"), cliente.map { it["name"] })
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("cidade", "uf"), (cliente[2]["fields"] as List<Map<String, Any?>>).map { it["name"] })
        @Suppress("UNCHECKED_CAST")
        val itens = byName["itens"]!!["fields"] as List<Map<String, Any?>>
        assertEquals(listOf("produto" to "string", "qtd" to "integer", "preco" to "money"), itens.map { it["name"] to it["type"] })
        // referência cíclica não explode: vira objeto sem subcampos
        assertEquals("object", byName["origem"]!!["type"])
        assertNull(byName["origem"]!!["fields"])
        // classe aninhada por nome com $ também
        assertNull(ClassFields.fields(project, "demo.Venda\$Item").error)
        assertEquals("classe não encontrada: x.Nada", ClassFields.fields(project, "x.Nada").error)
    }

    fun testFindClassesInProject() {
        addDomain()
        val found = ClassFields.findClasses(project, "vend")
        assertEquals("demo.Venda", found.first())
        assertTrue(ClassFields.findClasses(project, "demo.cli").contains("demo.Cliente"))
    }

    fun testIntentionFillsFieldsKeepingLabels() {
        addDomain()
        myFixture.configureByText(
            "vendas.report.json",
            """
            {
              "dataClass": "demo.Cliente",
              "fields": [ { "name": "nome", "type": "string", "label": "Razão social" }, { "name": "velho" } ],
              "body": []
            }
            """.trimIndent(),
        )
        val intention = myFixture.findSingleIntention("Importar campos da classe")
        myFixture.launchAction(intention)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("{ \"name\": \"nome\", \"type\": \"string\", \"label\": \"Razão social\" }"))
        assertTrue(text, text.contains("\"endereco\""))
        assertFalse(text, text.contains("velho"))
    }

    fun testIntentionInsertsFieldsWhenMissing() {
        addDomain()
        myFixture.configureByText("x.report.json", "{\n  \"dataClass\": \"demo.Endereco\",\n  \"body\": []\n}")
        myFixture.launchAction(myFixture.findSingleIntention("Importar campos da classe"))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\"fields\": [ { \"name\": \"cidade\", \"type\": \"string\" }, { \"name\": \"uf\", \"type\": \"string\" } ]"))
    }

    fun testWarningOnUnknownFieldAndCompletion() {
        myFixture.configureByText(
            "r.report.json",
            """
            {
              "fields": [ { "name": "valor", "type": "money" }, { "name": "cliente", "type": "object", "fields": [ { "name": "nome" } ] } ],
              "body": [ { "type": "text", "value": "Total: ${'$'}{vlor} ${'$'}{cliente.<caret>}" } ]
            }
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.contains("não existe") == true }
        assertEquals(warnings.toString(), 1, warnings.size)
        assertEquals("\${vlor}", myFixture.editor.document.text.substring(warnings[0].startOffset, warnings[0].endOffset))
        myFixture.completeBasic()
        assertEquals(listOf("nome"), myFixture.lookupElementStrings)
    }

    fun testCompletionInsideTableUsesItemScope() {
        myFixture.configureByText(
            "r.report.json",
            """
            {
              "fields": [ { "name": "numero" }, { "name": "itens", "type": "list", "fields": [ { "name": "produto" }, { "name": "qtd" } ] } ],
              "body": [ { "type": "table", "source": "itens", "columns": [ { "value": "${'$'}{pro<caret>}" } ] } ]
            }
            """.trimIndent(),
        )
        myFixture.completeBasic()
        // único candidato: completa direto
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\${produto}"))
        assertTrue(myFixture.file is JsonFile)
    }
}
