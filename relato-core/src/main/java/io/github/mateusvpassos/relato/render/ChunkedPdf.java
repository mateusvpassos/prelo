package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.ReportException;
import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF de tabelas muito grandes em blocos: o layout do HTML→PDF guarda o documento inteiro em memória, então
 * a tabela é dividida em blocos que terminam exatamente numa quebra de página (medida), cada bloco vira um
 * PDF temporário, os PDFs são juntados em disco e os números de página ("página X de Y") são carimbados no fim.
 */
final class ChunkedPdf {
    private ChunkedPdf() {}

    /** A maior tabela do corpo (1º nível) tem linhas bastante para valer a pena? (tabelas com pageTotals ficam fora) */
    static boolean applicable(Node.Document doc, int chunkRows) {
        int i = tableIndex(doc);
        return i >= 0 && ((Node.Table) doc.body().get(i)).rows().size() > chunkRows * 3 / 2;
    }

    private static int tableIndex(Node.Document doc) {
        int best = -1, size = 0;
        for (int i = 0; i < doc.body().size(); i++) {
            if (doc.body().get(i) instanceof Node.Table t && t.pageTotals() == null && t.rows().size() > size) {
                best = i;
                size = t.rows().size();
            }
        }
        return best;
    }

    private record Range(int from, int to, int pages) {}

    static void render(Node.Document doc, List<PdfRenderer.FontFile> fonts, int chunkRows, OutputStream out) {
        int ti = tableIndex(doc);
        Node.Table table = (Node.Table) doc.body().get(ti);
        List<Node.TRow> rows = table.rows();
        int n = rows.size();

        // passe 1: onde cada bloco termina (numa quebra de página) e quantas páginas tem
        List<Range> ranges = new ArrayList<>();
        int start = 0;
        while (start < n) {
            int end = Math.min(n, start + chunkRows);
            boolean last = end == n;
            Node.Document d = chunk(doc, ti, table, rows.subList(start, end), start == 0, last, true);
            PdfRenderer.Layout l = PdfRenderer.measure(HtmlRenderer.render(d, HtmlRenderer.PageNumbers.SLOTS, 4), fonts, "rl-r-");
            int pages = l.pageTop().length;
            if (last) {
                ranges.add(new Range(start, end, pages));
                break;
            }
            // linhas que caíram na última página passam para o próximo bloco
            int lastPage = pages - 1;
            int cut = end - start;
            for (int i = 0; i < end - start; i++) {
                PdfRenderer.Placement p = l.byId().get("rl-r-" + i);
                if (p != null && p.page() == lastPage) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) cut = end - start; // bloco inteiro numa página só: segue sem cortar
            else pages = lastPage;
            ranges.add(new Range(start, start + cut, pages));
            start += cut;
        }
        int total = ranges.stream().mapToInt(Range::pages).sum();
        int digits = String.valueOf(total).length();
        List<float[]> slots = probe(doc, fonts, digits);

        // passe 2: gera cada bloco em arquivo temporário e junta em disco
        List<Path> temps = new ArrayList<>();
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (int k = 0; k < ranges.size(); k++) {
                Range r = ranges.get(k);
                Node.Document d = chunk(doc, ti, table, rows.subList(r.from(), r.to()), k == 0, k == ranges.size() - 1, false);
                Path tmp = Files.createTempFile("relato-", ".pdf");
                temps.add(tmp);
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    PdfRenderer.render(HtmlRenderer.render(d, HtmlRenderer.PageNumbers.SLOTS, digits), fonts, os);
                }
                merger.addSource(tmp.toFile());
            }
            Path merged = Files.createTempFile("relato-merged-", ".pdf");
            temps.add(merged);
            merger.setDestinationFileName(merged.toString());
            RandomAccessStreamCache.StreamCacheCreateFunction cache = MemoryUsageSetting.setupTempFileOnly().streamCache;
            merger.mergeDocuments(cache);
            stampPageNumbers(merged, total, slots, doc, out);
        } catch (IOException e) {
            throw new ReportException("falha ao gerar PDF em blocos: " + e.getMessage(), e);
        } finally {
            for (Path p : temps) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temporário
                }
            }
        }
    }

    /** Documento de um bloco: conteúdo antes da tabela só no 1º, depois dela só no último. */
    private static Node.Document chunk(Node.Document doc, int ti, Node.Table table, List<Node.TRow> rows, boolean first, boolean last,
                                       boolean measure) {
        List<Node> body = new ArrayList<>();
        if (first) body.addAll(doc.body().subList(0, ti));
        Node.Table t = new Node.Table(table.columns(), rows, table.showHeader(), table.zebra(), table.grid(), table.emptyText(), table.css(),
                measure ? new Node.PageTotals(null, null, new boolean[table.columns().size()], new String[table.columns().size()]) : null);
        body.add(t);
        if (last) body.addAll(doc.body().subList(ti + 1, doc.body().size()));
        return new Node.Document(doc.title(), doc.lang(), doc.page(), doc.baseCss(), doc.extraCss(), null,
                first ? doc.firstPageHeader() : List.of(), doc.pageHeader(), body, doc.pageFooter(), null);
    }

    /**
     * Onde ficam os números de página: gera uma página só com cabeçalho/rodapé, com o marcador dentro do
     * espaço reservado, e lê a posição dele. Cada item: x, y (a partir do topo), tamanho da fonte, tipo (0=página, 1=total).
     */
    private static List<float[]> probe(Node.Document doc, List<PdfRenderer.FontFile> fonts, int digits) throws ReportException {
        Node.Document d = new Node.Document(doc.title(), doc.lang(), doc.page(), doc.baseCss(), doc.extraCss(), null,
                List.of(), doc.pageHeader(), List.of(), doc.pageFooter(), null);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        PdfRenderer.render(HtmlRenderer.render(d, HtmlRenderer.PageNumbers.PROBE, digits), fonts, bytes);
        List<float[]> out = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(bytes.toByteArray())) {
            PDFTextStripper st = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> pos) {
                    find(text, pos, HtmlRenderer.PAGE_MARK, 0);
                    find(text, pos, HtmlRenderer.PAGES_MARK, 1);
                }

                private void find(String text, List<TextPosition> pos, String mark, int kind) {
                    for (int i = text.indexOf(mark); i >= 0 && i < pos.size(); i = text.indexOf(mark, i + 1)) {
                        TextPosition a = pos.get(i);
                        out.add(new float[] {a.getXDirAdj(), a.getYDirAdj(), a.getFontSizeInPt(), kind});
                    }
                }
            };
            st.setStartPage(1);
            st.setEndPage(1);
            st.getText(pdf);
        } catch (IOException e) {
            throw new ReportException("falha ao medir o rodapé: " + e.getMessage(), e);
        }
        return out;
    }

    /** Escreve os números de página nos espaços reservados (mesma posição em todas as páginas). */
    private static void stampPageNumbers(Path pdf, int total, List<float[]> slots, Node.Document source, OutputStream out) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), MemoryUsageSetting.setupTempFileOnly().streamCache)) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                pageNo++;
                if (slots.isEmpty()) continue;
                float h = page.getMediaBox().getHeight();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    for (float[] m : slots) {
                        float size = m[2] <= 0 ? 8 : m[2];
                        cs.beginText();
                        cs.setFont(font, size);
                        cs.setNonStrokingColor(new Color(0x22, 0x22, 0x22));
                        cs.newLineAtOffset(m[0], h - m[1]);
                        cs.showText(String.valueOf(m[3] == 0 ? pageNo : total));
                        cs.endText();
                    }
                }
            }
            PdfPost.apply(doc, source);
            doc.save(out);
        }
    }
}
