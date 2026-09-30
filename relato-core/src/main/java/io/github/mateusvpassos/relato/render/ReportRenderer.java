package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.ResourceResolver;
import io.github.mateusvpassos.relato.expr.Formatter;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Ponto de entrada de baixo nível: template + dados → HTML, PDF ou XLSX. Prefira {@link io.github.mateusvpassos.relato.Relato}. */
public final class ReportRenderer {
    private final Formatter formatter;
    private final ResourceResolver resources;
    private final List<PdfRenderer.FontFile> fonts;

    public ReportRenderer(Formatter formatter, ResourceResolver resources, List<Font> fonts) {
        this.formatter = formatter;
        this.resources = resources;
        this.fonts = fonts.stream().map(f -> new PdfRenderer.FontFile(f.data(), f.family(), f.weight(), f.italic())).toList();
    }

    /** Fonte TrueType extra (para caracteres fora do Latin-1 ou identidade visual). */
    public record Font(Supplier<InputStream> data, String family, int weight, boolean italic) {}

    public String html(ReportTemplate t, Object data, Map<String, ?> params) {
        return HtmlRenderer.render(new Evaluator(formatter, resources).evaluate(t, data, params));
    }

    public void pdf(ReportTemplate t, Object data, Map<String, ?> params, OutputStream out) {
        Node.Document doc = new Evaluator(formatter, resources).evaluate(t, data, params);
        PdfRenderer.render(HtmlRenderer.render(doc), doc.title(), fonts, out);
    }

    public void xlsx(ReportTemplate t, Object data, Map<String, ?> params, OutputStream out) {
        Node.Document doc = new Evaluator(formatter, resources).evaluate(t, data, params);
        XlsxRenderer.render(doc, doc.title().isBlank() ? t.name() : doc.title(), out);
    }
}
