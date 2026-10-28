package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.expr.Formatter;
import io.github.mateusvpassos.prelo.expr.Values;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * "A transportar" / "Transporte": o HTML não sabe onde as páginas quebram, então mede a paginação,
 * decide as quebras (reservando espaço para as linhas de total), força-as e confere. Repete se alguma
 * página estourar.
 */
final class PageTotals {
    private PageTotals() {}

    private static final int MAX_ATTEMPTS = 6;

    /** Documento com as linhas de total por página inseridas (ou o original, se não há tabela com pageTotals). */
    static Node.Document apply(Node.Document doc, List<PdfRenderer.FontFile> fonts, Formatter fmt) {
        Node.Table table = find(doc.body());
        if (table == null || table.rows().isEmpty()) return doc;

        PdfRenderer.Layout first = PdfRenderer.measure(HtmlRenderer.render(doc), fonts, "rl-r-");
        int n = table.rows().size();
        int[] heights = new int[n];
        int[] pages = new int[n];
        int[] tops = new int[n];
        int maxH = 0;
        for (int i = 0; i < n; i++) {
            PdfRenderer.Placement p = first.byId().get("rl-r-" + i);
            if (p == null) return doc; // tabela não mediu (ex.: dentro de algo não paginado)
            heights[i] = p.height();
            pages[i] = p.page();
            tops[i] = p.top();
            maxH = Math.max(maxH, p.height());
        }
        if (pages[n - 1] == pages[0]) return doc; // cabe numa página: não há o que transportar

        int extra = maxH + 2; // altura reservada para cada linha de total
        int cap1 = first.pageBottom()[pages[0]] - tops[0];
        int capN = cap1;
        for (int i = 1; i < n; i++) {
            if (pages[i] != pages[i - 1]) {
                capN = first.pageBottom()[pages[i]] - tops[i];
                break;
            }
        }

        Node.Document result = doc;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<Integer> cuts = pack(heights, cap1, capN, extra);
            if (cuts.isEmpty()) return doc;
            Node.Table withTotals = table.withRows(insert(table, cuts, fmt));
            result = replace(doc, table, withTotals);
            PdfRenderer.Layout check = PdfRenderer.measure(HtmlRenderer.render(result), fonts, "rl-");
            int bad = firstOverflow(check, cuts, n);
            if (bad < 0) return result;
            // a página "bad" estourou: reserva mais espaço e tenta de novo
            if (bad == 0) cap1 -= extra;
            else capN -= extra;
        }
        return result;
    }

    /** Quebras (índice da última linha de cada página) empacotando as alturas na capacidade de cada página. */
    static List<Integer> pack(int[] heights, int cap1, int capN, int extra) {
        List<Integer> cuts = new ArrayList<>();
        int cap = cap1;
        int used = 0;
        for (int i = 0; i < heights.length; i++) {
            boolean last = i == heights.length - 1;
            int need = used + heights[i] + (last ? 0 : extra);
            if (need > cap && i > 0 && (cuts.isEmpty() || cuts.get(cuts.size() - 1) < i - 1)) {
                cuts.add(i - 1);
                cap = capN;
                used = extra; // linha "Transporte" no topo
            }
            used += heights[i];
        }
        return cuts;
    }

    /** Índice do primeiro trecho cujo "a transportar" não ficou na mesma página que as linhas dele; -1 se ok. */
    private static int firstOverflow(PdfRenderer.Layout l, List<Integer> cuts, int n) {
        int start = 0;
        for (int k = 0; k < cuts.size(); k++) {
            PdfRenderer.Placement carry = l.byId().get("rl-pt-c" + k);
            PdfRenderer.Placement firstRow = l.byId().get("rl-r-" + start);
            if (carry == null || firstRow == null) return k;
            for (int i = start; i <= cuts.get(k); i++) {
                PdfRenderer.Placement p = l.byId().get("rl-r-" + i);
                if (p == null || p.page() != carry.page()) return k;
            }
            PdfRenderer.Placement brought = l.byId().get("rl-pt-b" + k);
            if (brought == null || brought.page() != carry.page() + 1) return k;
            start = cuts.get(k) + 1;
        }
        return -1;
    }

    private static List<Node.TRow> insert(Node.Table t, List<Integer> cuts, Formatter fmt) {
        Node.PageTotals pt = t.pageTotals();
        int cols = t.columns().size();
        BigDecimal[] acc = new BigDecimal[cols];
        List<Node.TRow> out = new ArrayList<>();
        int k = 0;
        for (int i = 0; i < t.rows().size(); i++) {
            Node.TRow r = t.rows().get(i);
            out.add(r);
            if (r instanceof Node.DataRow d) {
                for (int c = 0; c < cols && c < d.cells().size(); c++) {
                    if (!pt.sum()[c] || d.cells().get(c) == null) continue;
                    Object raw = d.cells().get(c).raw();
                    if (!(raw instanceof Number)) continue;
                    BigDecimal v = Values.toNumber(raw);
                    acc[c] = acc[c] == null ? v : acc[c].add(v);
                }
            }
            if (k < cuts.size() && cuts.get(k) == i) {
                List<Node.Cell> cells = new ArrayList<>(cols);
                for (int c = 0; c < cols; c++) {
                    if (!pt.sum()[c]) {
                        cells.add(null);
                        continue;
                    }
                    BigDecimal v = acc[c] == null ? BigDecimal.ZERO : acc[c];
                    cells.add(new Node.Cell(fmt.format(v, pt.format()[c]), v, pt.format()[c]));
                }
                out.add(new Node.PageTotalRow(k, true, pt.carryLabel(), cells));
                out.add(new Node.PageTotalRow(k, false, pt.broughtLabel(), cells));
                k++;
            }
        }
        return out;
    }

    // ---------- busca/substituição da tabela na árvore

    private static Node.Table find(List<Node> nodes) {
        for (Node n : nodes) {
            if (n instanceof Node.Table t && t.pageTotals() != null) return t;
            List<Node> kids = children(n);
            if (kids != null) {
                Node.Table t = find(kids);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static List<Node> children(Node n) {
        if (n instanceof Node.Box b) return b.content();
        if (n instanceof Node.Labels l) return l.items().stream().flatMap(List::stream).toList();
        if (n instanceof Node.Row r) {
            List<Node> all = new ArrayList<>();
            for (Node.Col c : r.columns()) all.addAll(c.content());
            return all;
        }
        if (n instanceof Node.ListBlock l) {
            List<Node> all = new ArrayList<>(l.header());
            for (List<Node> item : l.items()) all.addAll(item);
            all.addAll(l.footer());
            return all;
        }
        return null;
    }

    private static Node.Document replace(Node.Document d, Node.Table from, Node.Table to) {
        UnaryOperator<List<Node>> f = new UnaryOperator<>() {
            public List<Node> apply(List<Node> nodes) {
                List<Node> out = new ArrayList<>(nodes.size());
                for (Node n : nodes) {
                    if (n == from) out.add(to);
                    else if (n instanceof Node.Box b) out.add(new Node.Box(apply(b.content()), b.keepTogether(), b.css()));
                    else if (n instanceof Node.Row r) {
                        List<Node.Col> cols = new ArrayList<>();
                        for (Node.Col c : r.columns()) cols.add(new Node.Col(c.width(), c.css(), apply(c.content())));
                        out.add(new Node.Row(cols, r.gap(), r.css()));
                    } else if (n instanceof Node.ListBlock l) {
                        List<List<Node>> items = new ArrayList<>();
                        for (List<Node> it : l.items()) items.add(apply(it));
                        out.add(new Node.ListBlock(apply(l.header()), items, apply(l.footer()), l.separator(), l.pageBreakBetween(),
                                l.keepTogether(), l.emptyText(), l.css()));
                    } else out.add(n);
                }
                return out;
            }
        };
        return new Node.Document(d.title(), d.lang(), d.page(), d.baseCss(), d.extraCss(), d.watermark(),
                d.firstPageHeader(), d.pageHeader(), f.apply(d.body()), d.pageFooter(), d.meta());
    }
}
