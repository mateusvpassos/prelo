package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.ReportException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Exporta para planilha: textos e campos viram linhas, tabelas viram linhas tipadas (números e datas de verdade),
 * com cabeçalho, grupos e totais. Cabeçalho/rodapé de página, imagens e linhas são ignorados.
 */
final class XlsxRenderer {
    private final Worksheet ws;
    private int row;
    private final Map<Integer, Integer> widths = new HashMap<>();

    private XlsxRenderer(Worksheet ws) {
        this.ws = ws;
    }

    static void render(Node.Document doc, String sheetName, OutputStream out) {
        try {
            Workbook wb = new Workbook(out, "relato", "1.0");
            String name = sheetName == null || sheetName.isBlank() ? "Relatório" : sheetName.replaceAll("[\\\\/?*\\[\\]:]", " ");
            Worksheet ws = wb.newWorksheet(name.length() > 31 ? name.substring(0, 31) : name);
            XlsxRenderer r = new XlsxRenderer(ws);
            r.nodes(doc.body());
            r.widths.forEach((c, w) -> ws.width(c, Math.min(60, Math.max(8, w + 2))));
            wb.finish();
        } catch (IOException e) {
            throw new ReportException("falha ao gerar XLSX: " + e.getMessage(), e);
        }
    }

    private void nodes(List<Node> nodes) {
        for (Node n : nodes) node(n);
    }

    private void node(Node n) {
        if (n instanceof Node.Text t) {
            String s = t.plain();
            if (!s.isBlank()) {
                put(row, 0, t.raw() != null && !(t.raw() instanceof String) ? t.raw() : s, t.format(), false);
                if (t.css().contains("bold")) ws.style(row, 0).bold().set();
                row++;
            }
        } else if (n instanceof Node.Row r) {
            // colunas lado a lado: cada coluna escreve seus textos numa coluna da planilha
            int start = row, end = row;
            for (int c = 0; c < r.columns().size(); c++) {
                int rr = start;
                for (Node inner : r.columns().get(c).content()) {
                    if (inner instanceof Node.Text t && !t.plain().isBlank()) put(rr++, c, t.plain(), null, false);
                    else if (inner instanceof Node.Fields f) rr = fieldsAt(f, rr, c * 2);
                }
                end = Math.max(end, rr);
            }
            row = end;
        } else if (n instanceof Node.Box b) nodes(b.content());
        else if (n instanceof Node.Table t) table(t);
        else if (n instanceof Node.ListBlock l) {
            nodes(l.header());
            for (List<Node> item : l.items()) nodes(item);
            nodes(l.footer());
        } else if (n instanceof Node.Fields f) row = fieldsAt(f, row, 0);
        else if (n instanceof Node.Spacer || n instanceof Node.PageBreak) row++;
    }

    private int fieldsAt(Node.Fields f, int r, int col) {
        for (Node.FieldCell c : f.items()) {
            put(r, col, c.label(), null, false);
            ws.style(r, col).bold().set();
            Node.Text v = c.value();
            put(r, col + 1, v.raw() != null && !(v.raw() instanceof String) ? v.raw() : v.plain(), v.format(), false);
            r++;
        }
        return r;
    }

    private void table(Node.Table t) {
        int ncols = t.columns().size();
        if (t.showHeader()) {
            for (int c = 0; c < ncols; c++) {
                put(row, c, t.columns().get(c).header(), null, false);
                ws.style(row, c).bold().fillColor("E9EDF2").borderStyle("thin").set();
            }
            row++;
        }
        for (Node.TRow tr : t.rows()) {
            if (tr instanceof Node.DataRow d) {
                for (int c = 0; c < ncols && c < d.cells().size(); c++) {
                    Node.Cell cell = d.cells().get(c);
                    if (cell != null) put(row, c, cell.raw() != null ? cell.raw() : cell.text(), cell.format(), true);
                }
                row++;
            } else if (tr instanceof Node.GroupHeader g) {
                put(row, 0, "  ".repeat(g.level()) + g.text(), null, false);
                ws.style(row, 0).bold().set();
                row++;
            } else if (tr instanceof Node.TotalRow tot) {
                boolean labeled = false;
                for (int c = 0; c < ncols; c++) {
                    Node.Cell cell = tot.cells().get(c);
                    if (cell != null) put(row, c, cell.raw() != null ? cell.raw() : cell.text(), cell.format(), true);
                    else if (!labeled) {
                        put(row, c, tot.label(), null, false);
                        labeled = true;
                    }
                    ws.style(row, c).bold().borderStyle(org.dhatim.fastexcel.BorderSide.TOP, "thin").set();
                }
                row++;
            }
        }
        if (t.rows().isEmpty() && !t.emptyText().isEmpty()) put(row++, 0, t.emptyText(), null, false);
        row++;
    }

    private void put(int r, int c, Object v, String format, boolean typed) {
        String width;
        if (v instanceof Number num && typed) {
            ws.value(r, c, num);
            String f = excelNumberFormat(format);
            if (f != null) ws.style(r, c).format(f).set();
            width = String.valueOf(num);
        } else if (typed && (v instanceof LocalDate || v instanceof LocalDateTime || v instanceof ZonedDateTime || v instanceof java.util.Date)) {
            boolean dateOnly = v instanceof LocalDate || v instanceof java.sql.Date;
            if (v instanceof LocalDate d) ws.value(r, c, d);
            else if (v instanceof LocalDateTime d) ws.value(r, c, d);
            else if (v instanceof ZonedDateTime d) ws.value(r, c, d);
            else ws.value(r, c, (java.util.Date) v);
            ws.style(r, c).format(dateOnly || "date".equals(format) ? "dd/mm/yyyy" : "dd/mm/yyyy hh:mm").set();
            width = "00/00/0000 00:00";
        } else if (v instanceof Boolean b && typed) {
            ws.value(r, c, b);
            width = "false";
        } else {
            String s = v == null ? "" : v.toString();
            if (!s.isEmpty()) ws.value(r, c, s);
            width = s;
        }
        widths.merge(c, Math.min(width.length(), 60), Math::max);
    }

    private static String excelNumberFormat(String f) {
        if (f == null) return null;
        return switch (f) {
            case "currency", "money" -> "\"R$\" #,##0.00";
            case "decimal" -> "#,##0.00";
            case "integer" -> "#,##0";
            case "percent" -> "0.00%";
            case "number" -> "General";
            default -> f.matches("[#0,.%\\s]+") ? f : null;
        };
    }
}
