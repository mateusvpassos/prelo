package prelo.intellij

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService

class PluginIntegrationTest : BasePlatformTestCase() {
    private val json = """{ "fields": [ { "name": "nome", "type": "string" } ],
        "body": [ { "type": "table", "columns": [ { "header": "Nome", "value": "${'$'}{nome}" } ] } ] }"""

    fun testSchemaIsMappedToReportFiles() {
        val file = myFixture.addFileToProject("vendas.report.json", json).virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        assertTrue("schema do prelo não associado: $schemas", schemas.any { it.name == "prelo.schema.json" })
        val other = myFixture.addFileToProject("outro.json", "{}").virtualFile
        assertFalse(JsonSchemaService.Impl.get(project).getSchemaFilesForFile(other).any { it.name == "prelo.schema.json" })
    }

    fun testSchemaFlagsUnknownProperty() {
        myFixture.configureByText("x.report.json", """{ "body": [ { "type": "text", <warning>"valor"</warning>: "x" } ] }""")
        myFixture.enableInspections(com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection::class.java)
        myFixture.checkHighlighting(true, false, false)
    }

    fun testPreviewPanelRenders() {
        val file = myFixture.addFileToProject("r.report.json", json).virtualFile
        assertTrue(ReportEditorProvider().accept(project, file))
        val panel = ReportPanel(project, file)
        try {
            val deadline = System.currentTimeMillis() + 30_000
            while (panel.lastResult == null && System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(50)
            }
            val r = panel.lastResult
            assertNotNull("preview não renderizou", r)
            assertNull(r!!.error)
            assertTrue(r.totalPages >= 1)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
