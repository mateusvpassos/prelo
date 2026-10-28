package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.expr.PageRef;
import io.github.mateusvpassos.prelo.model.Page;
import java.util.List;

/** Gera XHTML com CSS de mídia paginada (cabeçalho/rodapé repetidos, números de página, tabelas paginadas). */
final class HtmlRenderer {
    /** Marcadores de nº de página usados para medir onde carimbar (modo em blocos). */
    static final String PAGE_MARK = "§";   // §: um caractere, cabe no espaço reservado
    static final String PAGES_MARK = "¶";  // ¶

    /**
     * Nº de página: contador CSS (normal); espaço vazio de largura fixa (SLOTS, carimbado depois);
     * ou o espaço com o marcador (PROBE, para achar a posição). Ver {@link ChunkedPdf}.
     */
    enum PageNumbers { CSS, SLOTS, PROBE }

    private final StringBuilder sb = new StringBuilder(16 * 1024);
    private final PageNumbers pageNumbers;
    private final int digits;

    private HtmlRenderer(PageNumbers pageNumbers, int digits) {
        this.pageNumbers = pageNumbers;
        this.digits = digits;
    }

    static String render(Node.Document doc) {
        return render(doc, PageNumbers.CSS, 0);
    }

    static String render(Node.Document doc, PageNumbers mode, int digits) {
        HtmlRenderer r = new HtmlRenderer(mode, digits);
        r.document(doc);
        return r.sb.toString();
    }

    private void document(Node.Document doc) {
        sb.append("<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"").append(escape(doc.lang())).append("\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\"/>\n<title>").append(escape(doc.title())).append("</title>\n<style>\n");
        sb.append(pageCss(doc.page(), !doc.pageHeader().isEmpty(), !doc.firstPageHeader().isEmpty(), !doc.pageFooter().isEmpty()));
        sb.append(BASE_CSS);
        if (!doc.baseCss().isEmpty()) sb.append("body{").append(doc.baseCss()).append("}\n");
        if (doc.extraCss() != null) sb.append(doc.extraCss()).append('\n');
        sb.append("</style>\n</head>\n<body>\n");
        if (doc.watermark() != null) {
            Node.Watermark w = doc.watermark();
            sb.append("<div class=\"rl-watermark\"><div class=\"rl-watermark-in\"").append(style(w.css())).append('>');
            if (w.imageSrc() != null) sb.append("<img src=\"").append(escape(w.imageSrc())).append("\" alt=\"\" style=\"max-width:100%\"/>");
            else sb.append(escape(w.text()));
            sb.append("</div></div>\n");
        }
        if (!doc.firstPageHeader().isEmpty()) {
            sb.append("<div class=\"rl-first-header\">");
            nodes(doc.firstPageHeader());
            sb.append("</div>\n");
        }
        if (!doc.pageHeader().isEmpty()) {
            sb.append("<div class=\"rl-page-header\">");
            nodes(doc.pageHeader());
            sb.append("</div>\n");
        }
        if (!doc.pageFooter().isEmpty()) {
            sb.append("<div class=\"rl-page-footer\">");
            nodes(doc.pageFooter());
            sb.append("</div>\n");
        }
        nodes(doc.body());
        sb.append("</body>\n</html>\n");
    }

    private static String pageCss(Page p, boolean header, boolean firstHeader, boolean footer) {
        String size = switch (p.size().toLowerCase()) {
            case "a3" -> "A3";
            case "a4" -> "A4";
            case "a5" -> "A5";
            case "letter" -> "letter";
            case "legal" -> "legal";
            default -> p.size();
        };
        boolean named = !size.matches(".*\\d+(mm|cm|in|pt|px).*");
        StringBuilder c = new StringBuilder("@page {\n  size: ").append(size);
        if (named) c.append(' ').append("landscape".equalsIgnoreCase(p.orientation()) ? "landscape" : "portrait");
        c.append(";\n  margin: ").append(p.margin()).append(";\n");
        if (header) c.append("  @top-center { content: element(rlHeader); vertical-align: bottom; width: 100%; padding-bottom: 3mm; }\n");
        if (footer) c.append("  @bottom-center { content: element(rlFooter); vertical-align: top; width: 100%; padding-top: 3mm; }\n");
        c.append("}\n");
        if (firstHeader) c.append("@page :first {\n  @top-center { content: element(rlFirstHeader); vertical-align: bottom; width: 100%; padding-bottom: 3mm; }\n}\n");
        return c.toString();
    }

    private void nodes(List<Node> nodes) {
        for (Node n : nodes) node(n);
    }

    private void node(Node n) {
        if (n instanceof Node.Text t) text(t);
        else if (n instanceof Node.Html h) {
            sb.append("<div class=\"rl-html\"").append(style(h.css())).append('>').append(h.html()).append("</div>\n");
        } else if (n instanceof Node.Image img) {
            sb.append("<div class=\"rl-image\"").append(style(img.wrapCss())).append("><img src=\"").append(escape(img.src())).append('"')
                    .append(style(img.css())).append(" alt=\"\"/></div>\n");
        } else if (n instanceof Node.Row r) row(r);
        else if (n instanceof Node.Box b) {
            sb.append("<div class=\"rl-box").append(b.keepTogether() ? " rl-keep" : "").append('"').append(style(b.css())).append(">\n");
            nodes(b.content());
            sb.append("</div>\n");
        } else if (n instanceof Node.Table t) table(t);
        else if (n instanceof Node.ListBlock l) list(l);
        else if (n instanceof Node.Fields f) fields(f);
        else if (n instanceof Node.Line l) sb.append("<div class=\"rl-line\"").append(style(l.css())).append("></div>\n");
        else if (n instanceof Node.Spacer s) sb.append("<div class=\"rl-spacer\"").append(style(Css.join("height:" + s.height(), s.css()))).append("></div>\n");
        else if (n instanceof Node.PageBreak) sb.append("<div class=\"rl-break\"></div>\n");
        else if (n instanceof Node.RichText rt) sb.append("<div class=\"rl-md\"").append(style(rt.css())).append('>').append(rt.html()).append("</div>\n");
        else if (n instanceof Node.Labels l) labels(l);
    }

    private void text(Node.Text t) {
        sb.append("<div class=\"rl-text\"").append(style(t.css())).append('>');
        segments(t.segments());
        sb.append("</div>\n");
    }

    private void segments(List<Object> segs) {
        int start = sb.length();
        for (Object s : segs) {
            if (s == PageRef.PAGE) pageRef("rl-page", PAGE_MARK);
            else if (s == PageRef.PAGES) pageRef("rl-pages", PAGES_MARK);
            else sb.append(escape(String.valueOf(s)).replace("\n", "<br/>"));
        }
        if (sb.length() == start) sb.append("&#160;");
    }

    private void pageRef(String cssClass, String mark) {
        if (pageNumbers == PageNumbers.CSS) {
            sb.append("<span class=\"").append(cssClass).append("\"></span>");
            return;
        }
        sb.append("<span class=\"rl-slot\" style=\"width:").append(Math.max(1, digits) * 0.56 + 0.25).append("em\">")
                .append(pageNumbers == PageNumbers.PROBE ? mark : "").append("</span>");
    }

    private void row(Node.Row r) {
        sb.append("<table class=\"rl-row\"").append(style(r.css())).append("><tr>");
        String gap = r.gap() == null ? "4mm" : r.gap();
        for (int i = 0; i < r.columns().size(); i++) {
            Node.Col c = r.columns().get(i);
            String css = Css.join(c.width() == null ? "" : "width:" + c.width(), i > 0 ? "padding-left:" + gap : "", c.css());
            sb.append("<td").append(style(css)).append(">\n");
            nodes(c.content());
            sb.append("</td>");
        }
        sb.append("</tr></table>\n");
    }

    private void table(Node.Table t) {
        List<Node.ColSpec> cols = t.columns();
        int n = Math.max(1, cols.size());
        String[] aligns = inferAligns(t);
        sb.append("<table class=\"rl-table").append(t.grid() ? " rl-grid" : "").append(t.zebra() ? " rl-zebra" : "").append('"')
                .append(style(t.css())).append(">\n");
        if (t.showHeader()) {
            sb.append("<thead><tr>");
            for (int i = 0; i < cols.size(); i++) {
                Node.ColSpec c = cols.get(i);
                sb.append("<th").append(style(Css.join(width(c), align(aligns[i], null), c.headerCss()))).append('>')
                        .append(escape(c.header())).append("</th>");
            }
            sb.append("</tr></thead>\n");
        }
        sb.append("<tbody>\n");
        boolean firstData = !t.showHeader();
        int dataIdx = 0;
        if (t.rows().isEmpty()) {
            sb.append("<tr><td class=\"rl-empty\" colspan=\"").append(n).append("\">").append(escape(t.emptyText())).append("&#160;</td></tr>\n");
        }
        int rowIdx = -1;
        for (Node.TRow row : t.rows()) {
            rowIdx++;
            if (row instanceof Node.DataRow d) {
                sb.append("<tr").append(t.zebra() && dataIdx % 2 == 1 ? " class=\"rl-odd\"" : "")
                        .append(rowId(t, rowIdx)).append('>');
                for (int i = 0; i < cols.size(); i++) {
                    Node.ColSpec c = cols.get(i);
                    Node.Cell cell = i < d.cells().size() ? d.cells().get(i) : null;
                    String css = Css.join(firstData ? width(c) : "", align(aligns[i], cell == null ? null : cell.raw()), c.css(),
                            cell == null ? null : cell.css());
                    sb.append("<td").append(style(css)).append('>').append(cell == null ? "" : escape(cell.text())).append("</td>");
                }
                sb.append("</tr>\n");
                firstData = false;
                dataIdx++;
            } else if (row instanceof Node.GroupHeader g) {
                sb.append("<tr class=\"rl-gh rl-gh-").append(g.level()).append('"').append(rowId(t, rowIdx))
                        .append(g.pageBreak() ? " style=\"page-break-before:always\"" : "").append("><td colspan=\"").append(n).append('"')
                        .append(style(g.css())).append('>').append(escape(g.text())).append("</td></tr>\n");
                dataIdx = 0;
            } else if (row instanceof Node.PageTotalRow pr) {
                sb.append("<tr class=\"rl-pt\" id=\"rl-pt-").append(pr.carry() ? "c" : "b").append(pr.id()).append('"')
                        .append(pr.carry() ? " style=\"page-break-after:always\"" : "").append('>');
                int first = 0;
                while (first < cols.size() && pr.cells().get(first) == null) first++;
                int i = 0;
                if (first > 0) {
                    sb.append("<td").append(first > 1 ? " colspan=\"" + first + "\"" : "").append('>').append(escape(pr.label())).append("</td>");
                    i = first;
                }
                for (; i < cols.size(); i++) {
                    Node.Cell cell = pr.cells().get(i);
                    sb.append("<td").append(style(align(aligns[i], cell == null ? null : cell.raw()))).append('>')
                            .append(cell == null ? "" : escape(cell.text())).append("</td>");
                }
                sb.append("</tr>\n");
            } else if (row instanceof Node.TotalRow tr) {
                sb.append("<tr class=\"").append(tr.level() < 0 ? "rl-total" : "rl-sub rl-sub-" + tr.level()).append('"').append(rowId(t, rowIdx)).append('>');
                // rótulo ocupa as colunas antes do primeiro total
                int firstTotal = 0;
                while (firstTotal < cols.size() && tr.cells().get(firstTotal) == null) firstTotal++;
                int i = 0;
                if (firstTotal > 0) {
                    sb.append("<td").append(firstTotal > 1 ? " colspan=\"" + firstTotal + "\"" : "").append(style(tr.css())).append('>')
                            .append(escape(tr.label())).append("</td>");
                    i = firstTotal;
                }
                for (; i < cols.size(); i++) {
                    Node.Cell cell = tr.cells().get(i);
                    sb.append("<td").append(style(Css.join(align(aligns[i], cell == null ? null : cell.raw()), tr.css()))).append('>')
                            .append(cell == null ? "" : escape(cell.text())).append("</td>");
                }
                sb.append("</tr>\n");
            }
        }
        sb.append("</tbody></table>\n");
    }

    /** Grade de etiquetas: tabela de células de tamanho fixo; uma tabela por folha quando há linhas por página. */
    private void labels(Node.Labels l) {
        int perPage = l.rowsPerPage() > 0 ? l.rowsPerPage() * l.columns() : Integer.MAX_VALUE;
        int total = l.items().size();
        for (int start = 0; start < Math.max(total, 1); start += perPage) {
            int end = Math.min(total, start + perPage);
            boolean breakAfter = end < total && l.rowsPerPage() > 0;
            // sem width: as colunas dividem a largura útil (90mm fixos estouravam a A4 com o espaçamento)
            boolean fill = l.width() == null;
            String w = fill ? String.format(java.util.Locale.ROOT, "%.4f%%", 100.0 / l.columns()) : l.width();
            sb.append("<table class=\"rl-labels\"").append(style(Css.join("border-spacing:" + l.gap(), fill ? "width:100%;table-layout:fixed" : "", l.css(),
                    breakAfter ? "page-break-after:always" : ""))).append(">\n");
            for (int i = start; i < end; i += l.columns()) {
                sb.append("<tr>");
                for (int c = 0; c < l.columns(); c++) {
                    int k = i + c;
                    String cell = Css.join("width:" + w, "height:" + l.height(), fill ? "" : "max-width:" + w, "padding:0",
                            l.border() ? "border:0.5pt dashed #9aa0a6" : "");
                    sb.append("<td class=\"rl-label\"").append(style(cell)).append('>');
                    if (k < end) {
                        sb.append("<div class=\"rl-label-in\"").append(style("height:" + l.height() + ";padding:" + l.padding() + ";box-sizing:border-box;overflow:hidden")).append('>');
                        nodes(l.items().get(k));
                        sb.append("</div>");
                    }
                    sb.append("</td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</table>\n");
            if (total == 0) break;
        }
    }

    private void list(Node.ListBlock l) {
        sb.append("<div class=\"rl-list\"").append(style(l.css())).append(">\n");
        nodes(l.header());
        if (l.items().isEmpty() && !l.emptyText().isEmpty()) {
            sb.append("<div class=\"rl-empty\">").append(escape(l.emptyText())).append("</div>\n");
        }
        for (int i = 0; i < l.items().size(); i++) {
            if (i > 0 && l.separator()) sb.append("<div class=\"rl-sep\"></div>\n");
            sb.append("<div class=\"rl-item").append(l.keepTogether() ? " rl-keep" : "").append('"')
                    .append(i > 0 && l.pageBreakBetween() ? " style=\"page-break-before:always\"" : "").append(">\n");
            nodes(l.items().get(i));
            sb.append("</div>\n");
        }
        nodes(l.footer());
        sb.append("</div>\n");
    }

    private void fields(Node.Fields f) {
        sb.append("<table class=\"rl-fields\"").append(style(f.css())).append(">\n<tr>");
        int used = 0;
        String lw = f.labelWidth() == null ? "" : "width:" + f.labelWidth();
        for (Node.FieldCell c : f.items()) {
            int span = Math.min(c.span(), f.columns());
            if (used + span > f.columns()) {
                sb.append("</tr>\n<tr>");
                used = 0;
            }
            sb.append("<td class=\"rl-label\"").append(style(lw)).append('>').append(escape(c.label())).append("</td>");
            sb.append("<td class=\"rl-value\"").append(span > 1 ? " colspan=\"" + (span * 2 - 1) + "\"" : "")
                    .append(style(c.value().css())).append('>');
            segments(c.value().segments());
            sb.append("</td>");
            used += span;
        }
        sb.append("</tr>\n</table>\n");
    }

    /** Alinhamento explícito ou, se ausente, à direita para colunas numéricas (pela 1ª linha com valor). */
    private static String[] inferAligns(Node.Table t) {
        String[] out = new String[t.columns().size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = t.columns().get(i).align();
            if (out[i] != null && !out[i].isBlank()) continue;
            for (Node.TRow r : t.rows()) {
                if (r instanceof Node.DataRow d && i < d.cells().size() && d.cells().get(i) != null && d.cells().get(i).raw() != null) {
                    out[i] = d.cells().get(i).raw() instanceof Number ? "right" : null;
                    break;
                }
            }
        }
        return out;
    }

    /** Id das linhas de tabelas com totais por página, para medir a paginação. */
    private static String rowId(Node.Table t, int idx) {
        return t.pageTotals() != null ? " id=\"rl-r-" + idx + "\"" : "";
    }

    private static String width(Node.ColSpec c) {
        return c.width() == null ? "" : "width:" + c.width();
    }

    private static String align(String align, Object raw) {
        if (align != null && !align.isBlank()) return "text-align:" + align;
        return raw instanceof Number ? "text-align:right" : "";
    }

    private static String style(String css) {
        return css == null || css.isBlank() ? "" : " style=\"" + escape(css) + "\"";
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') continue;
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static final String BASE_CSS = """
            body { font-family: sans-serif; font-size: 9pt; color: #222; margin: 0; line-height: 1.25; word-wrap: break-word; }
            .rl-page-header { position: running(rlHeader); }
            table.rl-labels { border-collapse: separate; table-layout: fixed; margin: 0; }
            table.rl-labels tr { page-break-inside: avoid; }
            td.rl-label { vertical-align: top; overflow: hidden; }
            .rl-md p { margin: 0 0 6pt 0; text-align: inherit; }
            .rl-md h1 { font-size: 16pt; margin: 8pt 0 6pt 0; }
            .rl-md h2 { font-size: 13pt; margin: 8pt 0 5pt 0; }
            .rl-md h3 { font-size: 11pt; margin: 6pt 0 4pt 0; }
            .rl-md ul, .rl-md ol { margin: 0 0 6pt 0; padding-left: 16pt; }
            .rl-md li { margin: 0 0 2pt 0; }
            .rl-md blockquote { margin: 4pt 0 6pt 0; padding-left: 8pt; border-left: 2pt solid #ccd2da; color: #555; }
            .rl-md code { font-family: monospace; background: #f3f4f6; }
            .rl-md hr { border: 0; border-top: 0.5pt solid #999; margin: 6pt 0; }
            .rl-first-header { position: running(rlFirstHeader); }
            .rl-watermark { position: fixed; left: 0; right: 0; top: 35%; text-align: center; z-index: -1; }
            .rl-watermark-in { display: inline-block; font-weight: bold; white-space: nowrap; }
            tr.rl-pt td { font-weight: bold; font-style: italic; background: #f7f8fa; border-top: 0.5pt solid #a9b1bc; }
            .rl-page-footer { position: running(rlFooter); }
            .rl-page::after { content: counter(page); }
            .rl-slot { display: inline-block; overflow: hidden; white-space: nowrap; vertical-align: baseline; text-align: left; }
            .rl-pages::after { content: counter(pages); }
            .rl-text { margin: 0 0 1.5pt 0; }
            .rl-image img { max-width: 100%; }
            .rl-line { border-top: 0.5pt solid #999; margin: 3pt 0; height: 0; }
            .rl-break { page-break-after: always; height: 0; }
            .rl-keep { page-break-inside: avoid; }
            .rl-box { margin: 0 0 4pt 0; }
            .rl-empty { color: #777; font-style: italic; padding: 4pt; }
            .rl-sep { border-top: 0.5pt dashed #bbb; margin: 5pt 0; height: 0; }
            table.rl-row { width: 100%; border-collapse: collapse; table-layout: fixed; }
            table.rl-row > tbody > tr > td, table.rl-row > tr > td { vertical-align: top; padding: 0; }
            table.rl-table { width: 100%; border-collapse: collapse; -fs-table-paginate: paginate; -fs-page-break-min-height: 1.5cm; margin: 2pt 0 6pt 0; }
            table.rl-table th { background: #e9edf2; font-weight: bold; text-align: left; padding: 3pt 4pt; border-bottom: 0.75pt solid #8a94a3; }
            table.rl-table td { padding: 2pt 4pt; border-bottom: 0.25pt solid #dde1e6; vertical-align: top; }
            table.rl-table tr { page-break-inside: avoid; }
            table.rl-grid th, table.rl-grid td { border: 0.25pt solid #b5bcc6; }
            table.rl-zebra tr.rl-odd td { background: #f5f7f9; }
            tr.rl-gh td { font-weight: bold; background: #f0f3f7; padding-top: 4pt; border-bottom: 0.5pt solid #a9b1bc; }
            tr.rl-gh-1 td { background: #f8f9fb; padding-left: 10pt; }
            tr.rl-gh-2 td { background: #fff; padding-left: 18pt; }
            tr.rl-sub td { font-weight: bold; border-top: 0.5pt solid #a9b1bc; background: #fafbfc; }
            tr.rl-total td { font-weight: bold; border-top: 1pt solid #444; border-bottom: none; padding-top: 3pt; }
            table.rl-fields { width: 100%; border-collapse: collapse; margin: 0 0 4pt 0; }
            table.rl-fields td { vertical-align: top; padding: 1.5pt 0; }
            table.rl-fields td.rl-label { color: #555; font-weight: bold; padding-right: 4pt; }
            table.rl-fields td.rl-value { padding-right: 10pt; }
            """;
}
