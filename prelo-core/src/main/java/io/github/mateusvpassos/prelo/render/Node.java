package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.model.Page;
import java.util.List;

/** Relatório já avaliado (expressões resolvidas), pronto para virar HTML/PDF ou XLSX. */
sealed interface Node {

    record Document(String title, String lang, Page page, String baseCss, String extraCss, Watermark watermark,
                    List<Node> firstPageHeader, List<Node> pageHeader, List<Node> body, List<Node> pageFooter, PdfMeta meta) {}

    /** Metadados e proteção do PDF, já avaliados (aplicados com PDFBox depois de gerar). */
    record PdfMeta(String author, String subject, String keywords, String creator,
                   String userPassword, String ownerPassword, boolean allowPrint, boolean allowCopy, boolean allowModify, boolean secured) {}

    /** Etiquetas: uma lista de blocos por etiqueta, em grade de tamanho fixo. */
    record Labels(List<List<Node>> items, int columns, int rowsPerPage, String width, String height, String gap, boolean border,
                  String padding, String css) implements Node {}

    /** Texto rico já convertido para XHTML (Markdown). {@code plain}: o mesmo sem marcação, para o XLSX. */
    record RichText(String html, String plain, String css) implements Node {}

    /** Marca d'água: texto ou imagem (data URI). */
    record Watermark(String text, String imageSrc, String color, double size, double angle, double opacity, String width) {
        String css() {
            return "color:" + color + ";font-size:" + size + "pt;opacity:" + opacity + ";transform:rotate(" + angle + "deg)"
                    + (width != null ? ";width:" + width : "");
        }
    }

    /** {@code segments}: String ou {@link io.github.mateusvpassos.prelo.expr.PageRef}. {@code raw}: valor cru quando o texto é uma só expressão. */
    record Text(List<Object> segments, Object raw, String format, String css) implements Node {
        String plain() {
            StringBuilder sb = new StringBuilder();
            for (Object s : segments) if (s instanceof String str) sb.append(str);
            return sb.toString();
        }
    }

    record Html(String html, String css) implements Node {}

    record Image(String src, String css, String wrapCss) implements Node {}

    record Row(List<Col> columns, String gap, String css) implements Node {}

    record Col(String width, String css, List<Node> content) {}

    record Box(List<Node> content, boolean keepTogether, String css) implements Node {}

    record Table(List<ColSpec> columns, List<TRow> rows, boolean showHeader, boolean zebra, boolean grid,
                 String emptyText, String css, PageTotals pageTotals) implements Node {
        Table withRows(List<TRow> newRows) {
            return new Table(columns, newRows, showHeader, zebra, grid, emptyText, css, pageTotals);
        }
    }

    /** Totais por página: colunas somadas ({@code sum[i]}) e rótulos. */
    record PageTotals(String carryLabel, String broughtLabel, boolean[] sum, String[] format) {}

    record ColSpec(String header, String width, String align, String css, String headerCss) {}

    /** Célula: texto formatado + valor cru (para XLSX tipado). */
    record Cell(String text, Object raw, String format, String css) {
        Cell(String text, Object raw, String format) {
            this(text, raw, format, null);
        }
    }

    sealed interface TRow {}

    record DataRow(List<Cell> cells) implements TRow {}

    record GroupHeader(int level, String text, boolean pageBreak, String css) implements TRow {}

    /** Subtotal (level >= 0) ou total geral (level = -1). {@code label} ocupa a 1ª coluna sem total. */
    record TotalRow(int level, String label, List<Cell> cells, String css) implements TRow {}

    /** "A transportar" no pé da página ({@code carry}) ou "Transporte" no topo da seguinte. {@code id}: nº da quebra. */
    record PageTotalRow(int id, boolean carry, String label, List<Cell> cells) implements TRow {}

    record ListBlock(List<Node> header, List<List<Node>> items, List<Node> footer, boolean separator,
                     boolean pageBreakBetween, boolean keepTogether, String emptyText, String css) implements Node {}

    record Fields(List<FieldCell> items, int columns, String labelWidth, String css) implements Node {}

    record FieldCell(String label, Text value, int span) {}

    record Line(String css) implements Node {}

    record Spacer(String height, String css) implements Node {}

    record PageBreak() implements Node {}
}
