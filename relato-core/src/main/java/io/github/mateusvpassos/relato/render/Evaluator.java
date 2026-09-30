package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.ReportException;
import io.github.mateusvpassos.relato.ResourceResolver;
import io.github.mateusvpassos.relato.expr.Expr;
import io.github.mateusvpassos.relato.expr.Formatter;
import io.github.mateusvpassos.relato.expr.Interpolated;
import io.github.mateusvpassos.relato.expr.PageRef;
import io.github.mateusvpassos.relato.expr.Parser;
import io.github.mateusvpassos.relato.expr.Scope;
import io.github.mateusvpassos.relato.expr.Values;
import io.github.mateusvpassos.relato.model.Element;
import io.github.mateusvpassos.relato.model.ParamDef;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Avalia o template com os dados, produzindo a árvore {@link Node}. */
public final class Evaluator {
    private static final Set<String> AGGREGATES = Set.of("sum", "avg", "min", "max", "count");
    private static final Map<String, Expr> BARE = new ConcurrentHashMap<>();

    private final Formatter formatter;
    private final ResourceResolver resources;
    private final Function<String, ReportTemplate> includes;
    private int includeDepth;

    public Evaluator(Formatter formatter, ResourceResolver resources) {
        this(formatter, resources, null);
    }

    /** @param includes resolve os templates usados em blocos {@code include} (pode ser nulo) */
    public Evaluator(Formatter formatter, ResourceResolver resources, Function<String, ReportTemplate> includes) {
        this.formatter = formatter;
        this.resources = resources;
        this.includes = includes;
    }

    Node.Document evaluate(ReportTemplate t, Object data, Map<String, ?> params) {
        Scope scope = rootScope(t, data, params);
        return new Node.Document(
                t.title() == null ? "" : text(t.title(), null, scope),
                formatter.locale().toLanguageTag(),
                t.page(),
                Css.of(t.style()),
                t.css(),
                watermark(t, scope),
                elements(t.firstPageHeader(), scope, "firstPageHeader"),
                elements(t.pageHeader(), scope, "pageHeader"),
                elements(t.body(), scope, "body"),
                elements(t.pageFooter(), scope, "pageFooter"));
    }

    Scope rootScope(ReportTemplate t, Object data, Map<String, ?> params) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (ParamDef d : t.parameters()) {
            if (d.name() != null && d.defaultValue() != null) p.put(d.name(), d.defaultValue());
        }
        if (params != null) p.putAll(params);
        Map<String, Object> pvars = new HashMap<>(p);
        pvars.put("param", p);
        pvars.put("params", p);
        Scope paramScope = Scope.root(formatter, pvars);

        List<?> rows = Values.isCollection(data) ? Values.toList(data) : data == null ? List.of() : List.of(data);
        Map<String, Object> dvars = new HashMap<>();
        dvars.put("rows", rows);
        dvars.put("data", data);
        return paramScope.child(Values.isCollection(data) ? null : data, dvars, rows);
    }

    private List<Node> elements(List<Element> els, Scope scope, String path) {
        List<Node> out = new ArrayList<>(els.size());
        for (int i = 0; i < els.size(); i++) {
            Element e = els.get(i);
            String p = path + "[" + i + "]";
            if (e == null) continue;
            try {
                if (e.visible() != null && !e.visible().isBlank() && !Values.truthy(expr(e.visible(), scope))) continue;
                Node n = element(e, scope, p);
                if (n != null) out.add(n);
            } catch (ReportException ex) {
                if (ex.path() != null) throw ex;
                throw new ReportException(ex.getMessage(), p + " (" + typeName(e) + ")", ex);
            } catch (RuntimeException ex) {
                throw new ReportException(String.valueOf(ex), p + " (" + typeName(e) + ")", ex);
            }
        }
        return out;
    }

    private Node element(Element e, Scope scope, String path) {
        String css = Css.of(e.style());
        if (e instanceof Element.Text t) return textNode(t.value(), t.format(), Css.join(align(t.align()), css), scope);
        if (e instanceof Element.Html h) {
            StringBuilder sb = new StringBuilder();
            for (Object part : Interpolated.of(nz(h.value())).parts()) {
                sb.append(part instanceof String s ? s : HtmlRenderer.escape(formatter.toText(((Expr) part).eval(scope))));
            }
            return new Node.Html(sb.toString(), css);
        }
        if (e instanceof Element.Image img) return image(img, css, scope);
        if (e instanceof Element.Row r) {
            List<Node.Col> cols = new ArrayList<>();
            for (int i = 0; i < r.columns().size(); i++) {
                Element.Column c = r.columns().get(i);
                cols.add(new Node.Col(c.width(), Css.join(align(c.align()), Css.of(c.style())),
                        elements(c.content(), scope, path + ".columns[" + i + "].content")));
            }
            return new Node.Row(cols, r.gap(), css);
        }
        if (e instanceof Element.Box b) return new Node.Box(elements(b.content(), scope, path + ".content"), Boolean.TRUE.equals(b.keepTogether()), css);
        if (e instanceof Element.Table t) return table(t, css, scope, path);
        if (e instanceof Element.ListBlock l) return list(l, css, scope, path);
        if (e instanceof Element.Fields f) {
            List<Node.FieldCell> cells = new ArrayList<>();
            for (Element.FieldItem it : f.items()) {
                cells.add(new Node.FieldCell(text(it.label(), null, scope), textNode(it.value(), it.format(), "", scope),
                        it.span() == null ? 1 : Math.max(1, it.span())));
            }
            return new Node.Fields(cells, f.columns() == null ? 2 : Math.max(1, f.columns()), f.labelWidth(), css);
        }
        if (e instanceof Element.Line l) {
            return new Node.Line(Css.join("border-top:" + (l.thickness() == null ? "0.5pt" : l.thickness())
                    + " solid " + (l.color() == null ? "#999" : l.color()), css));
        }
        if (e instanceof Element.Spacer sp) return new Node.Spacer(sp.height() == null ? "5mm" : sp.height(), css);
        if (e instanceof Element.PageBreak) return new Node.PageBreak();
        if (e instanceof Element.Barcode b) return barcode(b, css, scope);
        if (e instanceof Element.Include inc) return include(inc, css, scope, path);
        throw new ReportException("elemento não suportado: " + e);
    }

    // ---------- marca d'água, código de barras, sub-relatório

    private Node.Watermark watermark(ReportTemplate t, Scope scope) {
        var w = t.watermark();
        if (w == null) return null;
        if (w.visible() != null && !w.visible().isBlank() && !Values.truthy(expr(w.visible(), scope))) return null;
        String img = null;
        if (w.src() != null && !w.src().isBlank()) {
            Object v = Interpolated.of(w.src()).value(scope);
            img = v instanceof byte[] b ? dataUri(b) : v == null ? null : resolveSrc(v.toString().trim());
        }
        String text = w.text() == null ? "" : text(w.text(), null, scope);
        if (img == null && text.isBlank()) return null;
        return new Node.Watermark(text, img, w.color() == null ? "#808080" : w.color(),
                w.size() == null ? 72 : w.size().doubleValue(), w.angle() == null ? -35 : w.angle().doubleValue(),
                w.opacity() == null ? 0.18 : w.opacity().doubleValue(), w.width());
    }

    private Node barcode(Element.Barcode b, String css, Scope scope) {
        String value = b.value() == null ? "" : text(b.value(), null, scope);
        if (value.isBlank()) return null;
        String fmt = b.format() == null ? "qr" : b.format();
        boolean twoD = Barcodes.isTwoDimensional(fmt);
        String w = b.width() != null ? b.width() : twoD ? "25mm" : "60mm";
        String h = b.height() != null ? b.height() : twoD ? w : "15mm";
        byte[] png = Barcodes.png(value, fmt);
        String imgCss = Css.join("width:" + w, "height:" + h, css);
        Node.Image img = new Node.Image(dataUri(png), imgCss, align(b.align()));
        if (twoD || !Boolean.TRUE.equals(b.showText())) return img;
        List<Node> parts = new ArrayList<>();
        parts.add(img);
        parts.add(new Node.Text(List.of(value), value, null, Css.join(align(b.align()), "font-family:monospace;font-size:8pt")));
        return new Node.Box(parts, true, "");
    }

    private Node include(Element.Include inc, String css, Scope scope, String path) {
        if (includes == null) throw new ReportException("include indisponível: nenhum carregador de templates configurado");
        if (inc.template() == null || inc.template().isBlank()) throw new ReportException("include sem 'template'");
        if (includeDepth >= 8) throw new ReportException("include aninhado demais (ciclo?) em '" + inc.template() + "'");
        ReportTemplate sub = includes.apply(inc.template());
        Object data = inc.data() == null || inc.data().isBlank() ? scope.currentRow() : expr(inc.data(), scope);
        Map<String, Object> params = new LinkedHashMap<>();
        Object parent = scope.lookupOrNull("params");
        if (parent instanceof Map<?, ?> pm) pm.forEach((k, v) -> params.put(String.valueOf(k), v));
        if (inc.params() != null) inc.params().forEach((k, v) -> params.put(k, v == null ? null : Interpolated.of(v).value(scope)));
        includeDepth++;
        try {
            Scope subScope = rootScope(sub, data, params);
            return new Node.Box(elements(sub.body(), subScope, path + " > " + inc.template() + ".body"), false, css);
        } finally {
            includeDepth--;
        }
    }

    private String resolveSrc(String s) {
        if (s.startsWith("data:") || s.startsWith("http://") || s.startsWith("https://")) return s;
        return dataUri(ResourceResolver.readOrThrow(resources, s));
    }

    // ---------- texto

    Node.Text textNode(String template, String format, String css, Scope scope) {
        Interpolated in = Interpolated.of(nz(template));
        List<Object> segs = new ArrayList<>();
        Object raw = null;
        boolean single = in.parts().size() == 1 && in.parts().get(0) instanceof Expr;
        for (Object part : in.parts()) {
            if (part instanceof String s) {
                segs.add(s);
                continue;
            }
            Object v = ((Expr) part).eval(scope);
            if (single) raw = v;
            segs.add(v instanceof PageRef ? v : formatter.format(v, format));
        }
        return new Node.Text(segs, single ? raw : null, format, css);
    }

    private String text(String template, String format, Scope scope) {
        if (template == null) return "";
        Interpolated in = Interpolated.of(template);
        if (format == null) return in.text(scope);
        StringBuilder sb = new StringBuilder();
        for (Object part : in.parts()) {
            if (part instanceof String s) sb.append(s);
            else {
                Object v = ((Expr) part).eval(scope);
                if (!(v instanceof PageRef)) sb.append(formatter.format(v, format));
            }
        }
        return sb.toString();
    }

    /** Propriedades "lógicas" (source, visible, by, sortBy): aceitam {@code itens} ou {@code ${itens}}. */
    static Object expr(String src, Scope scope) {
        if (src.contains("${")) return Interpolated.of(src).value(scope);
        return BARE.computeIfAbsent(src.trim(), Parser::parse).eval(scope);
    }

    private static Expr bare(String src) {
        String s = src.trim();
        if (s.startsWith("${") && s.endsWith("}") && Interpolated.of(s).parts().size() == 1) {
            return (Expr) Interpolated.of(s).parts().get(0);
        }
        return BARE.computeIfAbsent(s, Parser::parse);
    }

    // ---------- imagem

    private Node image(Element.Image img, String css, Scope scope) {
        Object v = img.src() == null ? null : Interpolated.of(img.src()).value(scope);
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        String src;
        if (v instanceof byte[] bytes) src = dataUri(bytes);
        else {
            String s = v.toString().trim();
            if (s.startsWith("data:") || s.startsWith("http://") || s.startsWith("https://")) src = s;
            else src = dataUri(ResourceResolver.readOrThrow(resources, s));
        }
        StringBuilder st = new StringBuilder();
        if (img.width() != null) st.append("width:").append(img.width()).append(';');
        if (img.height() != null) st.append("height:").append(img.height()).append(';');
        return new Node.Image(src, Css.join(st.toString(), css), align(img.align()));
    }

    Formatter formatter() {
        return formatter;
    }

    private static String dataUri(byte[] b) {
        String mime = "application/octet-stream";
        if (b.length > 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') mime = "image/png";
        else if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) mime = "image/jpeg";
        else if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') mime = "image/gif";
        else if (b.length > 2 && b[0] == 'B' && b[1] == 'M') mime = "image/bmp";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(b);
    }

    // ---------- tabela

    private Node table(Element.Table t, String css, Scope scope, String path) {
        List<?> items = sorted(Values.toList(expr(t.source() == null ? "rows" : t.source(), scope)), t.sortBy(), scope);
        List<Node.ColSpec> specs = new ArrayList<>();
        for (Element.TableColumn c : t.columns()) {
            specs.add(new Node.ColSpec(c.header() == null ? "" : text(c.header(), null, scope), c.width(),
                    c.align(), Css.of(c.style()), Css.of(c.headerStyle())));
        }
        Scope tableScope = scope.child(null, null, items);
        List<Node.TRow> rows = new ArrayList<>();
        int[] index = {0};
        emitGroup(t, 0, items, tableScope, rows, index, path, true);

        boolean hasTotals = t.columns().stream().anyMatch(c -> c.total() != null && !c.total().isBlank());
        if (hasTotals && !items.isEmpty()) {
            rows.add(new Node.TotalRow(-1, t.totalLabel() == null ? "Total" : text(t.totalLabel(), null, tableScope),
                    totals(t, tableScope), ""));
        }
        Node.PageTotals pt = null;
        if (Boolean.TRUE.equals(t.pageTotals())) {
            boolean[] sum = new boolean[t.columns().size()];
            String[] fmt = new String[t.columns().size()];
            for (int i = 0; i < sum.length; i++) {
                sum[i] = "sum".equals(t.columns().get(i).total());
                fmt[i] = t.columns().get(i).format();
            }
            pt = new Node.PageTotals(t.carryLabel() == null ? "A transportar" : t.carryLabel(),
                    t.broughtLabel() == null ? "Transporte" : t.broughtLabel(), sum, fmt);
        }
        return new Node.Table(specs, rows, !Boolean.FALSE.equals(t.showHeader()), Boolean.TRUE.equals(t.zebra()),
                Boolean.TRUE.equals(t.grid()), t.emptyText() == null ? "" : text(t.emptyText(), null, scope), css, pt);
    }

    private void emitGroup(Element.Table t, int level, List<?> items, Scope scope, List<Node.TRow> out, int[] index,
                           String path, boolean firstAtLevel) {
        if (level >= t.groups().size()) {
            for (Object item : items) {
                index[0]++;
                Scope rs = scope.child(item, Map.of("index", index[0]), null);
                List<Node.Cell> cells = new ArrayList<>(t.columns().size());
                for (int i = 0; i < t.columns().size(); i++) {
                    Element.TableColumn c = t.columns().get(i);
                    try {
                        cells.add(cell(c.value(), c.format(), rs));
                    } catch (ReportException e) {
                        throw new ReportException(e.getMessage() + " (linha " + index[0] + ")", path + ".columns[" + i + "].value", e);
                    }
                }
                out.add(new Node.DataRow(cells));
            }
            return;
        }
        Element.Group g = t.groups().get(level);
        if (g.by() == null || g.by().isBlank()) throw new ReportException("grupo sem 'by'", path + ".groups[" + level + "]", null);
        Expr by = bare(g.by());
        Map<Object, List<Object>> groups = new LinkedHashMap<>();
        for (Object item : items) {
            Object key = by.eval(scope.withRow(item));
            if (key instanceof Number n) key = Values.normalize(Values.toNumber(n));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        boolean first = firstAtLevel;
        for (Map.Entry<Object, List<Object>> e : groups.entrySet()) {
            List<Object> gItems = e.getValue();
            Scope gs = scope.child(null, Map.of("groupKey", e.getKey() == null ? "" : e.getKey()), gItems);
            Scope headScope = gs.withRow(gItems.get(0));
            String header = g.header() == null ? formatter.toText(e.getKey()) : text(g.header(), null, headScope);
            out.add(new Node.GroupHeader(level, header, level == 0 && Boolean.TRUE.equals(g.pageBreak()) && !first, Css.of(g.style())));
            emitGroup(t, level + 1, gItems, gs, out, index, path, first);
            if (Boolean.TRUE.equals(g.footer())) {
                String label = g.footerLabel() == null ? "Subtotal" : text(g.footerLabel(), null, headScope);
                out.add(new Node.TotalRow(level, label, totals(t, gs), ""));
            }
            first = false;
        }
    }

    private Node.Cell cell(String template, String format, Scope scope) {
        if (template == null) return new Node.Cell("", null, format);
        Interpolated in = Interpolated.of(template);
        if (in.parts().size() == 1 && in.parts().get(0) instanceof Expr e) {
            Object v = e.eval(scope);
            return new Node.Cell(formatter.format(v, format), v, format);
        }
        String s = text(template, format, scope);
        return new Node.Cell(s, s, null);
    }

    private List<Node.Cell> totals(Element.Table t, Scope scope) {
        List<Node.Cell> cells = new ArrayList<>();
        for (Element.TableColumn c : t.columns()) {
            String spec = c.total() == null ? "" : c.total().trim();
            if (spec.isEmpty()) {
                cells.add(null);
            } else if (AGGREGATES.contains(spec)) {
                if ("count".equals(spec)) {
                    Object v = new Expr.Call("count", List.of()).eval(scope);
                    cells.add(new Node.Cell(formatter.toText(v), v, null));
                } else {
                    Interpolated in = Interpolated.of(nz(c.value()));
                    if (!(in.parts().size() == 1 && in.parts().get(0) instanceof Expr ve)) {
                        throw new ReportException("total '" + spec + "' exige que 'value' seja uma única expressão (coluna " + c.header() + ")");
                    }
                    Object v = new Expr.Call(spec, List.of(ve)).eval(scope);
                    cells.add(new Node.Cell(formatter.format(v, c.format()), v, c.format()));
                }
            } else {
                cells.add(cell(spec, spec.contains("${") ? c.format() : null, scope));
            }
        }
        return cells;
    }

    // ---------- lista

    private Node list(Element.ListBlock l, String css, Scope scope, String path) {
        if (l.source() == null) throw new ReportException("list sem 'source'");
        List<?> items = sorted(Values.toList(expr(l.source(), scope)), l.sortBy(), scope);
        Scope ls = scope.child(null, null, items);
        String as = l.as() == null || l.as().isBlank() ? "item" : l.as();
        List<List<Node>> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(as, items.get(i));
            vars.put("index", i + 1);
            out.add(elements(l.content(), ls.child(items.get(i), vars, null), path + ".content"));
        }
        return new Node.ListBlock(elements(l.header(), ls, path + ".header"), out, elements(l.footer(), ls, path + ".footer"),
                Boolean.TRUE.equals(l.separator()), Boolean.TRUE.equals(l.pageBreakBetween()), Boolean.TRUE.equals(l.keepTogether()),
                l.emptyText() == null ? "" : text(l.emptyText(), null, scope), css);
    }

    private List<?> sorted(List<?> items, String sortBy, Scope scope) {
        if (sortBy == null || sortBy.isBlank() || items.size() < 2) return items;
        Comparator<Object> cmp = null;
        for (String key : splitTopLevel(sortBy)) {
            boolean desc = key.startsWith("-");
            Expr e = bare(desc ? key.substring(1) : key);
            Comparator<Object> c = Comparator.comparing(it -> new Sortable(e.eval(scope.withRow(it))));
            if (desc) c = c.reversed();
            cmp = cmp == null ? c : cmp.thenComparing(c);
        }
        List<Object> copy = new ArrayList<>(items);
        copy.sort(cmp);
        return copy;
    }

    private record Sortable(Object v) implements Comparable<Sortable> {
        public int compareTo(Sortable o) {
            return Values.compare(v, o.v);
        }
    }

    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static String align(String a) {
        return a == null || a.isBlank() ? "" : "text-align:" + a;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String typeName(Element e) {
        String n = e.getClass().getSimpleName();
        if (n.equals("ListBlock")) return "list";
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }
}
