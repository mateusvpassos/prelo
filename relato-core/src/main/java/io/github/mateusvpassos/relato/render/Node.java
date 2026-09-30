package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.model.Page;
import java.util.List;

/** Relatório já avaliado (expressões resolvidas), pronto para virar HTML/PDF ou XLSX. */
sealed interface Node {

    record Document(String title, String lang, Page page, String baseCss, String extraCss,
                    List<Node> pageHeader, List<Node> body, List<Node> pageFooter) {}

    /** {@code segments}: String ou {@link io.github.mateusvpassos.relato.expr.PageRef}. {@code raw}: valor cru quando o texto é uma só expressão. */
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
                 String emptyText, String css) implements Node {}

    record ColSpec(String header, String width, String align, String css, String headerCss) {}

    /** Célula: texto formatado + valor cru (para XLSX tipado). */
    record Cell(String text, Object raw, String format) {}

    sealed interface TRow {}

    record DataRow(List<Cell> cells) implements TRow {}

    record GroupHeader(int level, String text, boolean pageBreak, String css) implements TRow {}

    /** Subtotal (level >= 0) ou total geral (level = -1). {@code label} ocupa a 1ª coluna sem total. */
    record TotalRow(int level, String label, List<Cell> cells, String css) implements TRow {}

    record ListBlock(List<Node> header, List<List<Node>> items, List<Node> footer, boolean separator,
                     boolean pageBreakBetween, boolean keepTogether, String emptyText, String css) implements Node {}

    record Fields(List<FieldCell> items, int columns, String labelWidth, String css) implements Node {}

    record FieldCell(String label, Text value, int span) {}

    record Line(String css) implements Node {}

    record Spacer(String height, String css) implements Node {}

    record PageBreak() implements Node {}
}
