package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.ReportException;
import io.github.mateusvpassos.prelo.ResourceResolver;
import io.github.mateusvpassos.prelo.expr.Expr;
import io.github.mateusvpassos.prelo.expr.Formatter;
import io.github.mateusvpassos.prelo.expr.Interpolated;
import io.github.mateusvpassos.prelo.expr.PageRef;
import io.github.mateusvpassos.prelo.expr.Parser;
import io.github.mateusvpassos.prelo.expr.Scope;
import io.github.mateusvpassos.prelo.expr.Values;
import io.github.mateusvpassos.prelo.model.Element;
import io.github.mateusvpassos.prelo.model.ParamDef;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
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
                elements(t.pageFooter(), scope, "pageFooter"),
                meta(t, scope));
    }

    private Node.PdfMeta meta(ReportTemplate t, Scope scope) {
        var m = t.metadata();
        var s = t.security();
        if (m == null && s == null) return null;
        String user = s == null || s.userPassword() == null ? "" : text(s.userPassword(), null, scope);
        String owner = s == null || s.ownerPassword() == null ? "" : text(s.ownerPassword(), null, scope);
        return new Node.PdfMeta(
                m == null || m.author() == null ? null : text(m.author(), null, scope),
                m == null || m.subject() == null ? null : text(m.subject(), null, scope),
                m == null || m.keywords() == null ? null : text(m.keywords(), null, scope),
                m == null || m.creator() == null ? null : text(m.creator(), null, scope),
                user, owner,
                s == null || !Boolean.FALSE.equals(s.allowPrint()),
                s == null || !Boolean.FALSE.equals(s.allowCopy()),
                s != null && Boolean.TRUE.equals(s.allowModify()),
                s != null);
    }

    Scope rootScope(ReportTemplate t, Object data, Map<String, ?> params) {
        // parâmetro declarado e não informado vale null (como no Jasper); não declarado também (mapa tolerante)
        Map<String, Object> p = new Values.LenientMap();
        for (ParamDef d : t.parameters()) {
            if (d.name() != null) p.put(d.name(), d.defaultValue());
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
        if (!Values.isCollection(data)) return paramScope.child(data, dvars, rows);
        // fora de tabela/lista, campos soltos (ex.: no cabeçalho de página) leem o 1º registro, como no Jasper
        Scope firstRow = rows.isEmpty() ? paramScope : paramScope.withRow(rows.get(0));
        return firstRow.child(null, dvars, rows);
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
        String css = dynCss(Css.of(e.style()), scope);
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
        if (e instanceof Element.Chart c) return chart(c, css, scope);
        if (e instanceof Element.Labels l) return labels(l, css, scope, path);
        if (e instanceof Element.Markdown md) return markdown(md, css, scope);
        if (e instanceof Element.Crosstab x) return crosstab(x, css, scope);
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
        // max-width:none: com o max-width:100% padrão, o openhtmltopdf ignora a largura quando o PNG é mais largo que a coluna
        String imgCss = Css.join("width:" + w, "height:" + h, "max-width:none", css);
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

    // ---------- gráfico

    private Node chart(Element.Chart c, String css, Scope scope) {
        List<?> items = sorted(Values.toList(expr(c.source() == null ? "rows" : c.source(), scope)), c.sortBy(), scope);
        Scope cs = scope.child(null, null, items);
        List<String> seriesNames = new ArrayList<>();
        List<String> seriesExprs = new ArrayList<>();
        List<String> seriesColors = new ArrayList<>();
        if (c.series().isEmpty()) {
            seriesNames.add(c.title() == null ? "" : c.title());
            seriesExprs.add(c.value() == null ? "${1}" : c.value());
            seriesColors.add(null);
        } else {
            for (Element.Series s : c.series()) {
                seriesNames.add(s.name() == null ? "" : text(s.name(), null, scope));
                seriesExprs.add(s.value());
                seriesColors.add(s.color());
            }
        }
        // categorias na ordem de chegada; valores somados por categoria
        Map<String, double[]> byCat = new LinkedHashMap<>();
        for (Object item : items) {
            Scope rs = cs.withRow(item);
            String cat = c.label() == null ? String.valueOf(byCat.size() + 1) : text(c.label(), null, rs);
            double[] acc = byCat.computeIfAbsent(cat, k -> new double[seriesExprs.size()]);
            for (int i = 0; i < seriesExprs.size(); i++) {
                Object v = Interpolated.of(seriesExprs.get(i)).value(rs);
                java.math.BigDecimal n = v instanceof Number || v instanceof CharSequence ? Values.toNumber(v) : null;
                if (n != null) acc[i] += n.doubleValue();
            }
        }
        List<String> cats = new ArrayList<>(byCat.keySet());
        if (c.limit() != null && c.limit() > 0 && cats.size() > c.limit()) {
            boolean pie = "pie".equals(c.chartType()) || "donut".equals(c.chartType());
            if (pie && c.limit() > 1) {
                // fatias além do limite somadas em "Outros"
                double[] rest = new double[seriesExprs.size()];
                for (String k : cats.subList(c.limit() - 1, cats.size())) {
                    double[] v = byCat.get(k);
                    for (int i = 0; i < rest.length; i++) rest[i] += v[i];
                }
                cats = new ArrayList<>(cats.subList(0, c.limit() - 1));
                String outros = byCat.containsKey("Outros") ? "Outros " : "Outros";
                byCat.put(outros, rest);
                cats.add(outros);
            } else {
                cats = cats.subList(0, c.limit());
            }
        }
        List<Charts.Series> series = new ArrayList<>();
        List<String> palette = c.colors().isEmpty() ? Charts.PALETTE : c.colors();
        for (int i = 0; i < seriesExprs.size(); i++) {
            double[] vals = new double[cats.size()];
            for (int j = 0; j < cats.size(); j++) vals[j] = byCat.get(cats.get(j))[i];
            String col = seriesColors.get(i) != null ? seriesColors.get(i) : palette.get(i % palette.size());
            series.add(new Charts.Series(seriesNames.get(i), Charts.color(col), vals));
        }
        double w = Charts.toPt(c.width(), 450), h = Charts.toPt(c.height(), 220);
        String fmt = c.format();
        byte[] png = Charts.png(c.chartType(), c.title() == null ? null : text(c.title(), null, scope), cats, series, w, h,
                !Boolean.FALSE.equals(c.legend()), !Boolean.FALSE.equals(c.showValues()),
                v -> fmt == null ? compact(v) : formatter.format(java.math.BigDecimal.valueOf(v), fmt));
        return new Node.Image(dataUri(png), Css.join("width:" + (c.width() == null ? "100%" : c.width()), css), align(c.align()));
    }

    /** Número curto para eixos e rótulos: 1.234, 12,5 mil, 3,2 mi. */
    private String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return formatter.format(java.math.BigDecimal.valueOf(Math.round(v / 100_000) / 10.0), "#,##0.#") + " mi";
        if (a >= 10_000) return formatter.format(java.math.BigDecimal.valueOf(Math.round(v / 100) / 10.0), "#,##0.#") + " mil";
        return formatter.format(java.math.BigDecimal.valueOf(v), "#,##0.##");
    }

    // ---------- etiquetas

    private Node labels(Element.Labels l, String css, Scope scope, String path) {
        List<?> items = sorted(Values.toList(expr(l.source() == null ? "rows" : l.source(), scope)), l.sortBy(), scope);
        Scope ls = scope.child(null, null, items);
        List<List<Node>> out = new ArrayList<>();
        int index = 0;
        for (Object item : items) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("item", item);
            vars.put("index", ++index);
            Scope is = ls.child(item, vars, null);
            int copies = 1;
            if (l.copies() != null && !l.copies().isBlank()) {
                java.math.BigDecimal n = Values.toNumber(expr(l.copies(), is));
                copies = n == null ? 0 : Math.max(0, Math.min(1000, n.intValue()));
            }
            List<Node> content = elements(l.content(), is, path + ".content");
            for (int c = 0; c < copies; c++) out.add(content);
        }
        return new Node.Labels(out, l.columns() == null ? 2 : Math.max(1, l.columns()), l.rowsPerPage() == null ? 0 : Math.max(0, l.rowsPerPage()),
                l.width(), l.height() == null ? "45mm" : l.height(), l.gap() == null ? "3mm" : l.gap(),
                Boolean.TRUE.equals(l.border()), l.padding() == null ? "3mm" : l.padding(), css);
    }

    // ---------- markdown

    private Node markdown(Element.Markdown md, String css, Scope scope) {
        Interpolated in = Interpolated.of(nz(md.value()));
        // expressões viram marcadores antes do Markdown (para o valor não ser interpretado como marcação)
        StringBuilder src = new StringBuilder();
        List<String> values = new ArrayList<>();
        for (Object part : in.parts()) {
            if (part instanceof String s) src.append(HtmlRenderer.escape(s));
            else {
                Object v = ((Expr) part).eval(scope);
                values.add(v instanceof PageRef ? "" : HtmlRenderer.escape(formatter.toText(v)));
                src.append('\uE000').append(values.size() - 1).append('\uE001');
            }
        }
        String html = Markdown.toXhtml(src.toString());
        StringBuilder outHtml = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\uE000(\\d+)\uE001").matcher(html);
        while (m.find()) m.appendReplacement(outHtml, java.util.regex.Matcher.quoteReplacement(values.get(Integer.parseInt(m.group(1)))));
        m.appendTail(outHtml);
        String plain = outHtml.toString().replaceAll("<br/>", "\n").replaceAll("</(p|h\\d|li|blockquote)>", "\n").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim();
        return new Node.RichText(outHtml.toString(), plain, Css.join(align(md.align()), css));
    }

    // ---------- tabela cruzada

    private Node crosstab(Element.Crosstab x, String css, Scope scope) {
        List<?> items = Values.toList(expr(x.source() == null ? "rows" : x.source(), scope));
        String agg = x.aggregate() == null ? "sum" : x.aggregate();
        Map<String, Map<String, List<Object>>> cells = new LinkedHashMap<>();
        java.util.Set<String> colKeys = new java.util.LinkedHashSet<>();
        Map<String, List<Object>> byRow = new LinkedHashMap<>(), byCol = new LinkedHashMap<>();
        List<Object> all = new ArrayList<>();
        Scope cs = scope.child(null, null, items);
        for (Object item : items) {
            Scope rs = cs.withRow(item);
            String r = x.rows() == null ? "" : text(x.rows(), null, rs);
            String c = x.columns() == null ? "" : text(x.columns(), null, rs);
            Object v = "count".equals(agg) ? 1 : x.value() == null ? null : Interpolated.of(x.value()).value(rs);
            colKeys.add(c);
            cells.computeIfAbsent(r, k -> new LinkedHashMap<>()).computeIfAbsent(c, k -> new ArrayList<>()).add(v);
            byRow.computeIfAbsent(r, k -> new ArrayList<>()).add(v);
            byCol.computeIfAbsent(c, k -> new ArrayList<>()).add(v);
            all.add(v);
        }
        List<String> cols = new ArrayList<>(colKeys);
        if ("asc".equalsIgnoreCase(x.sortColumns())) cols.sort(null);
        else if ("desc".equalsIgnoreCase(x.sortColumns())) cols.sort(java.util.Comparator.reverseOrder());
        boolean totals = !Boolean.FALSE.equals(x.totals());
        String totalLabel = x.totalLabel() == null ? "Total" : text(x.totalLabel(), null, scope);
        List<Node.ColSpec> specs = new ArrayList<>();
        specs.add(new Node.ColSpec(x.rowHeader() == null ? "" : text(x.rowHeader(), null, scope), null, null, "font-weight:bold", ""));
        for (String c : cols) specs.add(new Node.ColSpec(c, null, "right", "", ""));
        if (totals) specs.add(new Node.ColSpec(totalLabel, null, "right", "font-weight:bold", ""));
        List<Node.TRow> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Object>>> r : cells.entrySet()) {
            List<Node.Cell> line = new ArrayList<>();
            line.add(new Node.Cell(r.getKey(), r.getKey(), null));
            for (String c : cols) line.add(aggCell(r.getValue().get(c), agg, x.format()));
            if (totals) line.add(aggCell(byRow.get(r.getKey()), agg, x.format()));
            rows.add(new Node.DataRow(line));
        }
        if (totals && !cells.isEmpty()) {
            List<Node.Cell> line = new ArrayList<>();
            line.add(null);
            for (String c : cols) line.add(aggCell(byCol.get(c), agg, x.format()));
            line.add(aggCell(all, agg, x.format()));
            rows.add(new Node.TotalRow(-1, totalLabel, line, ""));
        }
        return new Node.Table(specs, rows, true, Boolean.TRUE.equals(x.zebra()), !Boolean.FALSE.equals(x.grid()), "", css, null);
    }

    private Node.Cell aggCell(List<Object> vals, String agg, String format) {
        if (vals == null || vals.isEmpty()) return new Node.Cell("", null, format);
        java.math.BigDecimal acc = null;
        int n = 0;
        for (Object v : vals) {
            java.math.BigDecimal b = v instanceof Number || v instanceof CharSequence ? Values.toNumber(v) : null;
            if (b == null) continue;
            n++;
            acc = switch (agg) {
                case "min" -> acc == null || b.compareTo(acc) < 0 ? b : acc;
                case "max" -> acc == null || b.compareTo(acc) > 0 ? b : acc;
                default -> acc == null ? b : acc.add(b);
            };
        }
        if (acc == null) return new Node.Cell("", null, format);
        if ("avg".equals(agg)) acc = Values.normalize(acc.divide(java.math.BigDecimal.valueOf(n), java.math.MathContext.DECIMAL64));
        if ("count".equals(agg)) acc = java.math.BigDecimal.valueOf(n);
        return new Node.Cell(formatter.format(acc, "count".equals(agg) ? null : format), acc, format);
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
            else if (img.src().contains("${")) {
                // caminho vindo de dado/parâmetro: imagem ausente não derruba o relatório
                byte[] b;
                try {
                    b = resources.read(s);
                } catch (java.io.IOException e) {
                    b = null;
                }
                if (b == null) return null;
                src = dataUri(b);
            } else src = dataUri(ResourceResolver.readOrThrow(resources, s));
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
            String colCss = Css.of(c.style());
            specs.add(new Node.ColSpec(c.header() == null ? "" : text(c.header(), null, scope), c.width(),
                    c.align(), colCss.contains("${") ? "" : colCss, Css.of(c.headerStyle())));
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
                        Node.Cell cell = cell(c.value(), c.format(), rs);
                        String colCss = Css.of(c.style());
                        if (colCss.contains("${")) cell = new Node.Cell(cell.text(), cell.raw(), cell.format(), dynCss(colCss, rs));
                        cells.add(cell);
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

    /** Estilo com expressões (formatação condicional): {@code "color": "${saldo < 0 ? 'red' : ''}"}. */
    private String dynCss(String css, Scope scope) {
        if (css == null || !css.contains("${")) return css;
        return text(css, null, scope).replaceAll("[a-z-]+:\\s*(;|$)", "");
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
