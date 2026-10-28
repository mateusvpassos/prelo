package io.github.mateusvpassos.prelo.render;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.PageBox;
import io.github.mateusvpassos.prelo.ReportException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** XHTML → PDF com OpenHTMLtoPDF (PDFBox). */
final class PdfRenderer {
    private PdfRenderer() {}

    /** Fonte TrueType registrada com um nome de família para uso no CSS/style. */
    record FontFile(Supplier<InputStream> data, String family, int weight, boolean italic) {}

    /** Posição de um elemento com id após a paginação. */
    record Placement(int page, int top, int height) {}

    /** Layout paginado: página, topo e altura de cada elemento com id; limites de cada página. */
    record Layout(Map<String, Placement> byId, int[] pageTop, int[] pageBottom) {}

    static void render(String html, List<FontFile> fonts, OutputStream out) {
        try {
            PdfRendererBuilder b = builder(html, fonts);
            b.toStream(out);
            b.run();
        } catch (ReportException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportException("falha ao gerar PDF: " + e.getMessage(), e);
        }
    }

    /** Faz o layout sem gerar o PDF e devolve onde cada elemento com id (prefixo dado) caiu. */
    static Layout measure(String html, List<FontFile> fonts, String idPrefix) {
        PdfRendererBuilder b = builder(html, fonts);
        b.toStream(OutputStream.nullOutputStream());
        try (PdfBoxRenderer r = b.buildPdfRenderer()) {
            r.layout();
            List<PageBox> pages = r.getRootBox().getLayer().getPages();
            int[] top = new int[pages.size()];
            int[] bottom = new int[pages.size()];
            for (int i = 0; i < pages.size(); i++) {
                top[i] = pages.get(i).getTop();
                bottom[i] = pages.get(i).getBottom();
            }
            Map<String, Placement> byId = new HashMap<>();
            collect(r.getRootBox(), idPrefix, top, bottom, byId);
            return new Layout(byId, top, bottom);
        } catch (ReportException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportException("falha na paginação: " + e.getMessage(), e);
        }
    }

    private static void collect(Box box, String prefix, int[] top, int[] bottom, Map<String, Placement> out) {
        var el = box.getElement();
        if (el != null) {
            String id = el.getAttribute("id");
            if (id != null && id.startsWith(prefix) && !out.containsKey(id)) {
                int y = box.getAbsY();
                int page = 0;
                while (page < bottom.length - 1 && y >= bottom[page]) page++;
                out.put(id, new Placement(page, y, box.getHeight()));
            }
        }
        for (int i = 0; i < box.getChildCount(); i++) collect(box.getChild(i), prefix, top, bottom, out);
    }

    private static PdfRendererBuilder builder(String html, List<FontFile> fonts) {
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        for (FontFile f : fonts) {
            b.useFont(f.data()::get, f.family(), f.weight(),
                    f.italic() ? PdfRendererBuilder.FontStyle.ITALIC : PdfRendererBuilder.FontStyle.NORMAL, true);
        }
        b.withHtmlContent(html, null);
        b.withProducer("prelo");
        return b;
    }
}
