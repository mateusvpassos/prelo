package prelo.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewEngineTest {
    private val template = """
        {
          "fields": [ { "name": "descricao", "type": "string" }, { "name": "valor", "type": "money" } ],
          "pageHeader": [ { "type": "text", "value": "Cabeçalho" } ],
          "body": [ { "type": "table", "columns": [
            { "header": "Descrição", "value": "${'$'}{descricao}" },
            { "header": "Valor", "value": "${'$'}{valor}", "format": "currency", "total": "sum" } ] } ],
          "pageFooter": [ { "type": "text", "value": "${'$'}{page}/${'$'}{pages}" } ]
        }
    """.trimIndent()

    @Test
    fun rendersWithGeneratedData() {
        val r = PreviewEngine.render(PreviewEngine.Input(template, null, null))
        assertNull(r.error)
        assertTrue(r.totalPages >= 1)
        assertEquals(r.totalPages, r.pages.size)
        assertTrue(r.notes.any { it.contains("fields") })
    }

    @Test
    fun usesSampleFile() {
        val sample = """{"params":{},"data":[{"descricao":"A","valor":10},{"descricao":"B","valor":5.5}]}"""
        val r = PreviewEngine.render(PreviewEngine.Input(template, sample, null))
        assertNull(r.error)
        assertEquals(1, r.totalPages)
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun reportsTemplateErrors() {
        val r = PreviewEngine.render(PreviewEngine.Input("""{"body":[{"type":"tabel"}]}""", null, null))
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("tabel"))
    }
}
