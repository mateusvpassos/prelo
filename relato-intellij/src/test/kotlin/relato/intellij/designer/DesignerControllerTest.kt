package relato.intellij.designer

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

class DesignerControllerTest : BasePlatformTestCase() {
    private val sent = CopyOnWriteArrayList<JsonNode>()
    private lateinit var controller: DesignerController

    private val json = """{
  // comentário é aceito
  "fields": [ { "name": "nome", "type": "string" } ],
  "body": [ { "type": "text", "value": "Olá" } ]
}"""

    private fun open(text: String = json): com.intellij.openapi.vfs.VirtualFile {
        val file = myFixture.addFileToProject("r.report.json", text).virtualFile
        controller = DesignerController(project, file) { sent.add(DesignerController.MAPPER.readTree(it)) }
        Disposer.register(testRootDisposable, controller)
        return file
    }

    private fun waitFor(type: String, count: Int = 1): List<JsonNode> {
        val deadline = System.currentTimeMillis() + 30_000
        while (sent.count { it["type"].asText() == type } < count && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val got = sent.filter { it["type"].asText() == type }
        assertTrue("esperava $count mensagem(ns) '$type', veio: ${sent.map { it["type"] }}", got.size >= count)
        return got
    }

    fun testReadySendsThemeAndNormalizedJsonWithSchema() {
        open()
        controller.onMessage("""{"type":"ready"}""")
        waitFor("theme")
        val load = waitFor("load").single()
        val model = DesignerController.MAPPER.readTree(load["json"].asText())
        assertEquals("Olá", model["body"][0]["value"].asText())
        assertTrue(load["schema"].asText().contains("\"definitions\""))
        assertEquals("r.report.json", load["fileName"].asText())
    }

    fun testChangeFromDesignerEditsDocumentWithUndo() {
        val file = open()
        myFixture.openFileInEditor(file)
        controller.onMessage("""{"type":"ready"}""")
        waitFor("load")
        val newJson = "{\n  \"body\": [ { \"type\": \"text\", \"value\": \"Novo\" } ]\n}\n"
        controller.onMessage(DesignerController.MAPPER.writeValueAsString(mapOf("type" to "change", "json" to newJson)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val doc = FileDocumentManager.getInstance().getDocument(file)!!
        assertEquals(newJson, doc.text)
        // a própria alteração não volta como 'load' (sem eco)
        Thread.sleep(400)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, sent.count { it["type"].asText() == "load" })
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(file) as TextEditor
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(editor))
    }

    fun testTextEditReloadsDesignerAndInvalidJsonIsReported() {
        val file = open()
        controller.onMessage("""{"type":"ready"}""")
        waitFor("load")
        val doc = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.setText("""{"body":[{"type":"line"}]}""") }
        val loads = waitFor("load", 2)
        assertTrue(loads.last()["json"].asText().contains("line"))
        assertFalse(loads.last().has("schema"))
        WriteCommandAction.runWriteCommandAction(project) { doc.setText("""{"body":[""") }
        val invalid = waitFor("invalid").single()
        assertTrue(invalid["message"].asText().isNotBlank())
    }

    fun testPreviewRendersPages() {
        open()
        controller.onMessage("""{"type":"preview"}""")
        val p = waitFor("preview").single()
        assertTrue(p["error"] == null || p["error"].isNull)
        assertEquals(1, p["total"].asInt())
        assertTrue(p["pages"][0].asText().startsWith("data:image/png;base64,"))
    }

    fun testReplaceMinimalKeepsCommonParts() {
        val file = open("abcXYZdef")
        val doc = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { DesignerController.replaceMinimal(doc, "abc12def") }
        assertEquals("abc12def", doc.text)
    }

    fun testDesignerTabRegisteredAfterSplitEditor() {
        val file = open()
        val ids = FileEditorProviderManager.getInstance().getProviderList(project, file).map { it.editorTypeId }
        assertTrue(ids.toString(), ids.contains("relato-report-editor"))
        assertTrue(ids.toString(), ids.contains("relato-designer"))
        assertTrue(ids.toString(), ids.indexOf("relato-report-editor") < ids.indexOf("relato-designer"))
        assertTrue(DesignerHtml.page().contains("window.relato = {"))
    }
}
