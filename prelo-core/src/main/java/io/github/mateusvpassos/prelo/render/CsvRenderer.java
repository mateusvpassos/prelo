package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.ReportException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV da primeira tabela (ou tabela cruzada) do relatório: cabeçalho + linhas de dados. Separador ';' e decimal
 * com vírgula (o Excel em português abre direto), UTF-8 com BOM.
 */
final class CsvRenderer {
    private CsvRenderer() {}

    static void render(Node.Document doc, OutputStream out) {
        Node.Table t = first(doc.body());
        if (t == null) throw new ReportException("CSV: o relatório não tem tabela");
        try {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            if (t.showHeader()) {
                List<String> h = new ArrayList<>();
                for (Node.ColSpec c : t.columns()) h.add(c.header());
                line(w, h);
            }
            for (Node.TRow r : t.rows()) {
                if (!(r instanceof Node.DataRow d)) continue;
                List<String> vals = new ArrayList<>();
                for (Node.Cell c : d.cells()) vals.add(value(c));
                line(w, vals);
            }
            w.flush();
        } catch (IOException e) {
            throw new ReportException("falha ao gerar CSV: " + e.getMessage(), e);
        }
    }

    /** Números sem separador de milhar (para o Excel somar); o resto como aparece no relatório. */
    private static String value(Node.Cell c) {
        if (c == null) return "";
        if (c.raw() instanceof Number n && !(c.format() != null && c.format().matches("cpf|cnpj|cep|cpfCnpj"))) {
            BigDecimal b = n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
            return b.stripTrailingZeros().toPlainString().replace('.', ',');
        }
        if (c.raw() instanceof TemporalAccessor || c.raw() instanceof java.util.Date) return c.text();
        return c.text();
    }

    private static void line(Writer w, List<String> vals) throws IOException {
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) w.write(';');
            String v = vals.get(i) == null ? "" : vals.get(i);
            if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = '"' + v.replace("\"", "\"\"") + '"';
            w.write(v);
        }
        w.write("\r\n");
    }

    private static Node.Table first(List<Node> nodes) {
        for (Node n : nodes) {
            if (n instanceof Node.Table t) return t;
            List<Node> kids = n instanceof Node.Box b ? b.content()
                    : n instanceof Node.ListBlock l ? l.items().stream().flatMap(List::stream).toList()
                    : n instanceof Node.Row r ? r.columns().stream().flatMap(c -> c.content().stream()).toList() : null;
            if (kids != null) {
                Node.Table t = first(kids);
                if (t != null) return t;
            }
        }
        return null;
    }
}
