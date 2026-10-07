package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.FontSource;
import io.github.mateusvpassos.relato.ResourceResolver;
import io.github.mateusvpassos.relato.expr.Formatter;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.Supplier;

/** Ponto de entrada de baixo nível: template + dados → HTML, PDF ou XLSX. Prefira {@link io.github.mateusvpassos.relato.Relato}. */
public final class ReportRenderer {
    private final Formatter formatter;
    private final ResourceResolver resources;
    private final Function<String, ReportTemplate> includes;
    private final List<PdfRenderer.FontFile> fonts = new ArrayList<>();
    private String defaultFamily;
    private int chunkRows = 4000;

    /** Tabelas com mais linhas que isso geram o PDF em blocos (memória limitada). 0 desliga. */
    public ReportRenderer chunkRows(int rows) {
        this.chunkRows = rows;
        return this;
    }

    public ReportRenderer(Formatter formatter, ResourceResolver resources, List<Font> fonts) {
        this(formatter, resources, fonts, null);
    }

    /**
     * @param includes resolve templates usados em blocos {@code include} (pode ser nulo)
     */
    public ReportRenderer(Formatter formatter, ResourceResolver resources, List<Font> fonts, Function<String, ReportTemplate> includes) {
        this.formatter = formatter;
        this.resources = resources;
        this.includes = includes;
        for (FontSource src : ServiceLoader.load(FontSource.class, ReportRenderer.class.getClassLoader())) {
            src.fonts().forEach(this::add);
            if (defaultFamily == null) defaultFamily = src.defaultFamily();
        }
        fonts.forEach(this::add);
    }

    private void add(Font f) {
        fonts.add(new PdfRenderer.FontFile(f.data(), f.family(), f.weight(), f.italic()));
    }

    /** Fonte TrueType extra (para caracteres fora do Latin-1 ou identidade visual). */
    public record Font(Supplier<InputStream> data, String family, int weight, boolean italic) {}

    public String html(ReportTemplate t, Object data, Map<String, ?> params) {
        return HtmlRenderer.render(evaluate(t, data, params));
    }

    public void pdf(ReportTemplate t, Object data, Map<String, ?> params, OutputStream out) {
        Node.Document evaluated = evaluate(t, data, params);
        if (chunkRows > 0 && ChunkedPdf.applicable(evaluated, chunkRows)) {
            ChunkedPdf.render(evaluated, fonts, chunkRows, out);
            return;
        }
        Node.Document doc = PageTotals.apply(evaluated, fonts, formatter);
        if (!PdfPost.needed(doc)) {
            PdfRenderer.render(HtmlRenderer.render(doc), fonts, out);
            return;
        }
        // marca d'água, metadados e senha: aplicados com PDFBox depois (o HTML→PDF não faz opacidade nem criptografia)
        Node.Document plain = new Node.Document(doc.title(), doc.lang(), doc.page(), doc.baseCss(), doc.extraCss(), null,
                doc.firstPageHeader(), doc.pageHeader(), doc.body(), doc.pageFooter(), null);
        java.io.ByteArrayOutputStream tmp = new java.io.ByteArrayOutputStream(64 * 1024);
        PdfRenderer.render(HtmlRenderer.render(plain), fonts, tmp);
        PdfPost.apply(tmp.toByteArray(), doc, out);
    }

    /** Primeira tabela do relatório em CSV (separador ';', UTF-8 com BOM para o Excel). */
    public void csv(ReportTemplate t, Object data, Map<String, ?> params, OutputStream out) {
        CsvRenderer.render(evaluate(t, data, params), out);
    }

    public void xlsx(ReportTemplate t, Object data, Map<String, ?> params, OutputStream out) {
        Node.Document doc = evaluate(t, data, params);
        XlsxRenderer.render(doc, doc.title().isBlank() ? t.name() : doc.title(), out);
    }

    private Node.Document evaluate(ReportTemplate t, Object data, Map<String, ?> params) {
        Node.Document d = new Evaluator(formatter, resources, includes).evaluate(t, data, params);
        if (defaultFamily == null) return d;
        return new Node.Document(d.title(), d.lang(), d.page(), Css.join("font-family:'" + defaultFamily + "', sans-serif", d.baseCss()),
                d.extraCss(), d.watermark(), d.firstPageHeader(), d.pageHeader(), d.body(), d.pageFooter(), d.meta());
    }
}
