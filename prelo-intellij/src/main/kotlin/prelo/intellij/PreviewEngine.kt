package prelo.intellij

import io.github.mateusvpassos.prelo.Format
import io.github.mateusvpassos.prelo.Prelo
import io.github.mateusvpassos.prelo.ReportException
import io.github.mateusvpassos.prelo.ResourceResolver
import io.github.mateusvpassos.prelo.SampleData
import io.github.mateusvpassos.prelo.TemplateLoader
import io.github.mateusvpassos.prelo.Templates
import io.github.mateusvpassos.prelo.model.ReportTemplate
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File

/** Renderiza um template para o preview, sem dependência da IDE (testável isolado). */
object PreviewEngine {
    const val SAMPLE_ROWS = 40
    const val MAX_PAGES = 40

    class Input(
        val templateText: String,
        val sampleText: String?,
        val baseDir: File?,
        /** Pastas de recursos do projeto, para resolver `classpath:` das imagens. */
        val resourceRoots: List<File> = emptyList(),
    )

    class Result(
        val template: ReportTemplate?,
        val sample: SampleData?,
        val pages: List<BufferedImage>,
        val totalPages: Int,
        val notes: List<String>,
        val error: String?,
    )

    fun render(input: Input, dpi: Float = 110f): Result {
        val notes = mutableListOf<String>()
        val template = try {
            Templates.parse(input.templateText)
        } catch (e: ReportException) {
            return Result(null, null, emptyList(), 0, notes, e.message)
        }
        val sample = try {
            sample(template, input.sampleText, notes)
        } catch (e: ReportException) {
            return Result(template, null, emptyList(), 0, notes, e.message)
        }
        return try {
            val pdf = produce(template, sample, input, Format.PDF)
            Loader.loadPDF(pdf).use { doc ->
                val r = PDFRenderer(doc)
                val n = doc.numberOfPages
                val shown = minOf(n, MAX_PAGES)
                val pages = (0 until shown).map { r.renderImageWithDPI(it, dpi, ImageType.RGB) }
                Result(template, sample, pages, n, notes, null)
            }
        } catch (e: ReportException) {
            Result(template, sample, emptyList(), 0, notes, e.message)
        } catch (e: Exception) {
            Result(template, sample, emptyList(), 0, notes, describe(e))
        }
    }

    fun produce(template: ReportTemplate, sample: SampleData, input: Input, format: Format): ByteArray =
        prelo(input).report(template).data(sample.data()).params(sample.params()).render(format)

    fun sample(template: ReportTemplate, sampleText: String?, notes: MutableList<String>): SampleData {
        if (sampleText != null) return SampleData.parse(sampleText, template)
        if (template.fields().isEmpty()) {
            notes += "Sem 'fields' nem arquivo .sample.json: tabelas e listas ficam vazias. Declare 'fields' ou crie o sample (botão na barra)."
        } else {
            notes += "Dados inventados a partir de 'fields' ($SAMPLE_ROWS linhas)."
        }
        return SampleData.generate(template, SAMPLE_ROWS)
    }

    private fun prelo(input: Input): Prelo {
        val standard = ResourceResolver.standard(PreviewEngine::class.java.classLoader, input.baseDir?.toPath())
        val resolver = ResourceResolver { location ->
            if (location.startsWith("classpath:")) {
                val rel = location.removePrefix("classpath:").trimStart('/')
                input.resourceRoots.map { File(it, rel) }.firstOrNull { it.isFile }?.readBytes()
            } else {
                standard.read(location)
            }
        }
        val b = Prelo.builder().resources(resolver).cache(false)
        // sub-relatórios: templates vizinhos na mesma pasta
        input.baseDir?.let { b.templates(TemplateLoader.directory(it.toPath())) }
        return b.build()
    }

    fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(5).joinToString("\ncausa: ") { "${it.javaClass.simpleName}: ${it.message}" }
        return chain
    }
}
