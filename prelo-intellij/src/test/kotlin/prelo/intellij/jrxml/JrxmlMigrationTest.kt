package prelo.intellij.jrxml

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JrxmlMigrationTest : BasePlatformTestCase() {
    fun testConvertsFolderNextToOriginals() {
        val xml = javaClass.getResource("/vendas-teste.jrxml")!!.readText()
        val f = myFixture.addFileToProject("relatorios/vendas-teste.jrxml", xml).virtualFile
        myFixture.addFileToProject("relatorios/outro.txt", "x")
        val files = JrxmlMigration.jrxmlFiles(arrayOf(f.parent))
        assertEquals(listOf("vendas-teste.jrxml"), files.map { it.name })
        val r = JrxmlMigration.convert(project, files, emptyMap())
        assertEquals(emptyList<String>(), r.failures)
        val out = f.parent.findChild("vendas-teste.report.json")
        assertNotNull(out)
        val text = VfsUtil.loadText(out!!)
        assertTrue(text, text.contains("\"total\": \"sum\""))
        assertTrue(text, text.contains("Página \${page}"))
    }
}
