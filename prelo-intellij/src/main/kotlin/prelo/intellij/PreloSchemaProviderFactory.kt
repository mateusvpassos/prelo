package prelo.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** Liga o schema embutido no prelo-core a todo *.report.json (autocomplete, documentação e validação). */
class PreloSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider)

    private object Provider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = ReportFiles.isReport(file)
        override fun getName(): String = "Prelo report"
        override fun getSchemaFile(): VirtualFile? =
            javaClass.getResource("/prelo.schema.json")?.let { VfsUtil.findFileByURL(it) }
        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
