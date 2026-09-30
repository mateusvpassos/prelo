package io.github.mateusvpassos.relato.render;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.github.mateusvpassos.relato.ReportException;
import java.io.OutputStream;
import java.util.List;

/** XHTML → PDF com OpenHTMLtoPDF (PDFBox). */
final class PdfRenderer {
    private PdfRenderer() {}

    /** Fonte TrueType registrada com um nome de família para uso no CSS/style. */
    record FontFile(java.util.function.Supplier<java.io.InputStream> data, String family, int weight, boolean italic) {}

    static void render(String html, String title, List<FontFile> fonts, OutputStream out) {
        try {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            for (FontFile f : fonts) {
                b.useFont(f.data()::get, f.family(), f.weight(),
                        f.italic() ? PdfRendererBuilder.FontStyle.ITALIC : PdfRendererBuilder.FontStyle.NORMAL, true);
            }
            b.withHtmlContent(html, null);
            b.withProducer("relato");
            b.toStream(out);
            b.run();
        } catch (ReportException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportException("falha ao gerar PDF: " + e.getMessage(), e);
        }
    }
}
