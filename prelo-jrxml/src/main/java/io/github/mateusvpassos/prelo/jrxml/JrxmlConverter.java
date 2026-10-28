package io.github.mateusvpassos.prelo.jrxml;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Converte um {@code .jrxml} (JasperReports 1.x a 6.x) num {@code .report.json} do prelo. É uma migração
 * assistida: o layout absoluto vira blocos em fluxo (linhas → colunas), o detail vira tabela (ou lista),
 * grupos e somas viram grupos/totais, sub-relatórios viram {@code include}. O que não dá para traduzir
 * vai para a lista de avisos (e para comentários no topo do JSON).
 */
public final class JrxmlConverter {

    /** @param json      conteúdo do .report.json (com comentários de revisão no topo, se houver avisos) */
    public record Result(String json, List<String> warnings, Stats stats) {}

    /**
     * @param subreportParams parâmetro → nome do template do sub-relatório (ver {@link SubreportHints})
     * @param knownTemplates  nomes dos templates existentes (para casar nomes aproximados)
     */
    public record Options(Map<String, String> subreportParams, Set<String> knownTemplates) {
        public static final Options NONE = new Options(Map.of(), Set.of());
    }

    /** Métricas da conversão (para relatórios em lote). */
    public record Stats(int expressions, int expressionsFailed, int elements, int elementsSkipped, String layout) {}

    private final List<String> warnings = new ArrayList<>();
    private int exprTotal, exprFailed, elTotal, elSkipped;

    private final Map<String, Element> variables = new LinkedHashMap<>();
    private final Map<String, Element> styles = new HashMap<>();
    private final Set<Element> consumed = new HashSet<>();
    private double bandWidth = 535;

    private final Options options;

    private JrxmlConverter(Options options) {
        this.options = options == null ? Options.NONE : options;
    }

    public static Result convert(byte[] xml, String name) {
        return convert(xml, name, Options.NONE);
    }

    public static Result convert(byte[] xml, String name, Options options) {
        return new JrxmlConverter(options).run(decode(xml), name);
    }

    public static Result convert(String xml, String name) {
        return new JrxmlConverter(Options.NONE).run(xml, name);
    }

    // ------------------------------------------------------------ leitura

    /** UTF-8 se válido; senão ISO-8859-1 (muitos .jrxml antigos declaram UTF-8 mas estão em Latin-1). */
    static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setValidating(false);
        f.setNamespaceAware(false);
        trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
        String clean = xml.replaceFirst("^\\uFEFF", "").replaceFirst("<\\?xml[^>]*\\?>", "");
        return b.parse(new InputSource(new StringReader(clean)));
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean v) {
        try {
            f.setFeature(feature, v);
        } catch (Exception ignored) {
            // implementação sem o recurso
        }
    }

    // ------------------------------------------------------------ conversão

    private Result run(String xml, String name) {
        Document doc;
        try {
            doc = parseXml(xml);
        } catch (Exception e) {
            throw new IllegalArgumentException("XML inválido: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!"jasperReport".equals(root.getTagName())) throw new IllegalArgumentException("não é um .jrxml (raiz <" + root.getTagName() + ">)");
        if (!children(root, "element").isEmpty() || root.getElementsByTagName("band").getLength() == 0 && root.getElementsByTagName("element").getLength() > 0) {
            warnings.add("formato JasperReports 7 (<element kind=...>) não é suportado");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", attr(root, "name", name));

        double pw = num(root, "pageWidth", 595), ph = num(root, "pageHeight", 842);
        double lm = num(root, "leftMargin", 20), rm = num(root, "rightMargin", 20), tm = num(root, "topMargin", 20), bm = num(root, "bottomMargin", 20);
        bandWidth = num(root, "columnWidth", pw - lm - rm);
        if (bandWidth <= 0) bandWidth = pw - lm - rm;
        Map<String, Object> page = new LinkedHashMap<>();
        boolean landscape = pw > ph;
        if (near(Math.min(pw, ph), 595) && near(Math.max(pw, ph), 842)) page.put("size", "A4");
        else if (near(Math.min(pw, ph), 612) && near(Math.max(pw, ph), 792)) page.put("size", "Letter");
        else page.put("size", fmt(pw) + "pt " + fmt(ph) + "pt");
        if (page.get("size").equals("A4") || page.get("size").equals("Letter")) page.put("orientation", landscape ? "landscape" : "portrait");
        page.put("margin", fmt(tm) + "pt " + fmt(rm) + "pt " + fmt(bm) + "pt " + fmt(lm) + "pt");
        out.put("page", page);

        for (Element s : children(root, "style")) styles.put(s.getAttribute("name"), s);
        for (Element v : children(root, "variable")) variables.put(v.getAttribute("name"), v);

        List<Map<String, Object>> params = new ArrayList<>();
        for (Element p : children(root, "parameter")) {
            String n = p.getAttribute("name");
            if (n.startsWith("REPORT_") || n.equals("SUBREPORT_DIR") || n.startsWith("JASPER_") || n.equals("IS_IGNORE_PAGINATION")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n);
            m.put("type", typeOf(p.getAttribute("class"), n));
            params.add(m);
        }
        if (!params.isEmpty()) out.put("parameters", params);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Element f : children(root, "field")) addField(fields, f.getAttribute("name"), f.getAttribute("class"));
        if (!fields.isEmpty()) out.put("fields", fields);

        // bandas
        List<El> title = band(root, "title");
        List<El> pageHeader = band(root, "pageHeader");
        List<El> columnHeader = band(root, "columnHeader");
        List<El> detail = band(root, "detail");
        List<El> columnFooter = band(root, "columnFooter");
        List<El> pageFooter = band(root, "pageFooter");
        List<El> lastPageFooter = band(root, "lastPageFooter");
        List<El> summary = band(root, "summary");
        List<El> noData = band(root, "noData");
        if (!lastPageFooter.isEmpty()) warnings.add("lastPageFooter ignorado (use visible com page == pages no pageFooter)");

        List<Element> groups = children(root, "group");
        List<List<El>> groupHeaders = new ArrayList<>(), groupFooters = new ArrayList<>();
        for (Element g : groups) {
            groupHeaders.add(bandsOf(child(g, "groupHeader")));
            groupFooters.add(bandsOf(child(g, "groupFooter")));
        }

        List<Object> body = new ArrayList<>();
        String layout;
        Map<String, Object> table = null;
        List<El> detailContent = content(detail);
        List<List<El>> detailLines = lines(detailContent);
        boolean tabular = detailLines.size() == 1 && detailContent.size() >= 2
                && detailContent.stream().allMatch(e -> e.kind.equals("textField") || e.kind.equals("staticText"));

        // seções de grupo com conteúdo "rico" (sub-relatórios, várias linhas) viram blocos no corpo
        List<Map<String, Object>> tableGroups = new ArrayList<>();
        List<Object> sectionBlocks = new ArrayList<>();
        if (tabular) {
            layout = "tabela";
            table = new LinkedHashMap<>();
            table.put("type", "table");
            List<El> cols = new ArrayList<>(detailContent);
            cols.sort(Comparator.comparingDouble(e -> e.x));
            List<Map<String, Object>> columns = new ArrayList<>();
            for (El c : cols) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("header", "");
                String value = c.kind.equals("staticText") ? escapeTemplate(text(c.node)) : fieldTemplate(c, true);
                col.put("value", value);
                col.put("width", pct(c.w));
                String align = align(c);
                if (align != null) col.put("align", align);
                String fmt = format(c);
                if (fmt != null) col.put("format", fmt);
                Map<String, Object> st = style(c, false);
                if (!st.isEmpty()) col.put("style", st);
                columns.add(col);
            }
            // cabeçalhos: a linha de textos fixos que melhor casa com as colunas
            List<El> headerLine = findHeaderLine(cols, columnHeader, pageHeader, groupHeaders);
            if (headerLine != null) {
                for (El h : headerLine) {
                    int i = bestColumn(cols, h);
                    if (i >= 0) {
                        String prev = (String) columns.get(i).get("header");
                        columns.get(i).put("header", (prev.isEmpty() ? "" : prev + " ") + text(h.node).trim());
                        consumed.add(h.node);
                    }
                }
            } else {
                warnings.add("cabeçalhos das colunas não identificados (preencha 'header')");
            }
            for (Map<String, Object> col : columns) if ("".equals(col.get("header"))) col.remove("header");

            for (int gi = 0; gi < groups.size(); gi++) {
                Element g = groups.get(gi);
                String gexpr = textOf(child(g, "groupExpression"));
                List<El> gh = content(groupHeaders.get(gi)).stream().filter(e -> !consumed.contains(e.node)).toList();
                boolean rich = gh.stream().anyMatch(e -> !e.kind.equals("textField") && !e.kind.equals("staticText")) || lines(gh).size() > 1;
                if (gexpr == null || gexpr.isBlank()) {
                    if (!gh.isEmpty()) sectionBlocks.addAll(blocks(gh));
                    continue;
                }
                Map<String, Object> grp = new LinkedHashMap<>();
                String by = bare(gexpr);
                if (by == null) {
                    warnings.add("grupo '" + g.getAttribute("name") + "': expressão não convertida");
                    continue;
                }
                grp.put("by", by);
                if (rich) {
                    sectionBlocks.addAll(blocks(gh));
                    warnings.add("grupo '" + g.getAttribute("name") + "': cabeçalho com sub-relatório/várias linhas foi para o corpo");
                } else if (!gh.isEmpty()) {
                    List<El> sorted = new ArrayList<>(gh);
                    sorted.sort(Comparator.comparingDouble(e -> e.x));
                    StringBuilder h = new StringBuilder();
                    for (El e : sorted) {
                        String t = e.kind.equals("staticText") ? escapeTemplate(text(e.node)) : fieldTemplate(e, false);
                        if (h.length() > 0 && !t.isBlank()) h.append(' ');
                        h.append(t.trim());
                        consumed.add(e.node);
                    }
                    grp.put("header", h.toString());
                }
                if (collectTotals(content(groupFooters.get(gi)), cols, columns, "Group", g.getAttribute("name"), grp, "footerLabel")) {
                    grp.put("footer", true);
                }
                if ("true".equals(g.getAttribute("isStartNewPage"))) grp.put("pageBreak", true);
                tableGroups.add(grp);
            }
            Map<String, Object> totalHolder = new LinkedHashMap<>();
            List<El> footerish = new ArrayList<>(content(columnFooter));
            footerish.addAll(content(summary));
            collectTotals(footerish, cols, columns, "Report", null, totalHolder, "totalLabel");
            if (totalHolder.containsKey("totalLabel")) table.put("totalLabel", totalHolder.get("totalLabel"));
            if (!tableGroups.isEmpty()) table.put("groups", tableGroups);
            table.put("columns", columns);
            List<El> nd = content(noData);
            if (!nd.isEmpty() && nd.get(0).kind.equals("staticText")) {
                table.put("emptyText", text(nd.get(0).node).trim());
                consumed.add(nd.get(0).node);
            }
        } else if (!detailContent.isEmpty()) {
            layout = "lista";
            table = new LinkedHashMap<>();
            table.put("type", "list");
            table.put("source", "rows");
            for (int gi = 0; gi < groups.size(); gi++) {
                List<El> gh = content(groupHeaders.get(gi));
                if (!gh.isEmpty()) {
                    sectionBlocks.addAll(blocks(gh));
                    if (textOf(child(groups.get(gi), "groupExpression")) != null && !textOf(child(groups.get(gi), "groupExpression")).isBlank()) {
                        warnings.add("grupo '" + groups.get(gi).getAttribute("name") + "' com detail em várias linhas: revise (lista não agrupa)");
                    }
                }
            }
            table.put("content", blocks(detailContent));
        } else {
            layout = "sem detail";
            for (List<El> gh : groupHeaders) sectionBlocks.addAll(blocks(content(gh)));
            for (List<El> gf : groupFooters) sectionBlocks.addAll(blocks(content(gf)));
        }

        body.addAll(blocks(content(title)));
        if ("true".equals(root.getAttribute("isTitleNewPage")) && !title.isEmpty()) body.add(Map.of("type", "pageBreak"));
        body.addAll(blocks(content(columnHeader)));
        body.addAll(sectionBlocks);
        if (table != null) body.add(table);
        if (tabular) {
            for (List<El> gf : groupFooters) {
                List<El> rest = content(gf);
                if (!rest.isEmpty()) warnings.add("conteúdo de rodapé de grupo sem equivalente (textos soltos): revise");
            }
        }
        body.addAll(blocks(content(columnFooter)));
        body.addAll(blocks(content(summary)));

        List<Object> headerBlocks = blocks(content(pageHeader));
        List<Object> pf = blocks(content(pageFooter));
        if (!headerBlocks.isEmpty()) out.put("pageHeader", headerBlocks);
        out.put("body", body);
        if (!pf.isEmpty()) out.put("pageFooter", pf);

        StringBuilder json = new StringBuilder();
        if (!warnings.isEmpty()) {
            json.append("// Migrado de ").append(name).append(".jrxml pelo prelo. Revisar:\n");
            for (String w : new java.util.LinkedHashSet<>(warnings)) json.append("//  - ").append(w.replace("\n", " ")).append('\n');
        }
        json.append(JsonPretty.write(out)).append('\n');
        return new Result(json.toString(), List.copyOf(new java.util.LinkedHashSet<>(warnings)),
                new Stats(exprTotal, exprFailed, elTotal, elSkipped, layout));
    }

    /** Campo "a.b.c" (propriedade aninhada do bean) vira objeto a → objeto b → campo c. */
    @SuppressWarnings("unchecked")
    private static void addField(List<Map<String, Object>> fields, String name, String cls) {
        String[] parts = name.split("\\.");
        List<Map<String, Object>> level = fields;
        for (int i = 0; i < parts.length; i++) {
            boolean leaf = i == parts.length - 1;
            Map<String, Object> found = null;
            for (Map<String, Object> f : level) if (parts[i].equals(f.get("name"))) found = f;
            if (found == null) {
                found = new LinkedHashMap<>();
                found.put("name", parts[i]);
                found.put("type", leaf ? typeOf(cls, parts[i]) : "object");
                level.add(found);
            }
            if (leaf) break;
            found.put("type", "object");
            level = (List<Map<String, Object>>) found.computeIfAbsent("fields", k -> new ArrayList<Map<String, Object>>());
        }
    }

    // ------------------------------------------------------------ elementos

    private record El(String kind, double x, double y, double w, double h, Element node) {
        double right() {
            return x + w;
        }

        double bottom() {
            return y + h;
        }
    }

    private List<El> band(Element root, String name) {
        return bandsOf(child(root, name));
    }

    /** Elementos de todas as {@code <band>} de uma seção (detail pode ter várias). */
    private List<El> bandsOf(Element section) {
        List<El> out = new ArrayList<>();
        if (section == null) return out;
        double yOff = 0;
        for (Element b : children(section, "band")) {
            collect(b, 0, yOff, out);
            yOff += num(b, "height", 0);
        }
        return out;
    }

    private void collect(Element container, double dx, double dy, List<El> out) {
        for (Element e : children(container, null)) {
            String tag = e.getTagName();
            Element re = child(e, "reportElement");
            switch (tag) {
                case "frame" -> {
                    if (re != null) collect(e, dx + num(re, "x", 0), dy + num(re, "y", 0), out);
                }
                case "elementGroup" -> collect(e, dx, dy, out);
                case "staticText", "textField", "image", "line", "rectangle", "ellipse", "subreport", "break",
                        "componentElement", "chart", "crosstab", "pieChart", "barChart", "lineChart", "genericElement" -> {
                    if (re == null) continue;
                    out.add(new El(tag, dx + num(re, "x", 0), dy + num(re, "y", 0), num(re, "width", 0), num(re, "height", 0), e));
                }
                default -> {
                    if (tag.endsWith("Chart")) out.add(new El("chart", dx, dy, 0, 0, e));
                }
            }
        }
    }

    /** Elementos com conteúdo (sem retângulos decorativos), ainda não usados. */
    private List<El> content(List<El> els) {
        List<El> out = new ArrayList<>();
        for (El e : els) {
            if (consumed.contains(e.node)) continue;
            if (e.kind.equals("rectangle") || e.kind.equals("ellipse")) continue;
            if (e.kind.equals("line") && e.w < e.h) continue; // linhas verticais
            out.add(e);
        }
        return out;
    }

    /** Agrupa em linhas visuais pela sobreposição vertical. */
    private static List<List<El>> lines(List<El> els) {
        List<El> sorted = new ArrayList<>(els);
        sorted.sort(Comparator.comparingDouble((El e) -> e.y).thenComparingDouble(e -> e.x));
        List<List<El>> out = new ArrayList<>();
        for (El e : sorted) {
            if (e.kind.equals("line")) {
                out.add(new ArrayList<>(List.of(e)));
                continue;
            }
            List<El> target = null;
            for (List<El> l : out) {
                if (l.get(0).kind.equals("line")) continue;
                double top = l.stream().mapToDouble(x -> x.y).min().orElse(0);
                double bot = l.stream().mapToDouble(El::bottom).max().orElse(0);
                double ov = Math.min(bot, e.bottom()) - Math.max(top, e.y);
                if (ov >= 0.5 * Math.max(1, Math.min(e.h, bot - top))) {
                    target = l;
                    break;
                }
            }
            if (target == null) out.add(new ArrayList<>(List.of(e)));
            else target.add(e);
        }
        out.sort(Comparator.comparingDouble(l -> l.get(0).y));
        return out;
    }

    private List<Object> blocks(List<El> els) {
        List<Object> out = new ArrayList<>();
        double lastBottom = -1;
        for (List<El> line : lines(els)) {
            double top = line.stream().mapToDouble(x -> x.y).min().orElse(0);
            if (lastBottom >= 0 && top - lastBottom > 10) out.add(new LinkedHashMap<>(Map.of("type", "spacer", "height", fmt(top - lastBottom) + "pt")));
            lastBottom = Math.max(lastBottom, line.stream().mapToDouble(El::bottom).max().orElse(top));
            line.sort(Comparator.comparingDouble(e -> e.x));
            if (line.size() == 1) {
                Map<String, Object> b = block(line.get(0));
                if (b != null) out.add(b);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "row");
            List<Object> cols = new ArrayList<>();
            double cursor = 0;
            for (El e : line) {
                if (e.x - cursor > bandWidth * 0.03 && cursor > 0 || e.x > bandWidth * 0.03 && cursor == 0) {
                    cols.add(new LinkedHashMap<>(Map.of("width", pct(e.x - cursor), "content", List.of())));
                }
                Map<String, Object> b = block(e);
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("width", pct(e.w));
                col.put("content", b == null ? List.of() : List.of(b));
                cols.add(col);
                cursor = e.right();
            }
            row.put("columns", cols);
            row.put("gap", "0");
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> block(El e) {
        elTotal++;
        consumed.add(e.node);
        Map<String, Object> b = new LinkedHashMap<>();
        String printWhen = printWhen(e);
        switch (e.kind) {
            case "staticText" -> {
                b.put("type", "text");
                b.put("value", escapeTemplate(text(e.node)));
            }
            case "textField" -> {
                b.put("type", "text");
                b.put("value", fieldTemplate(e, false));
                String f = format(e);
                if (f != null) b.put("format", f);
            }
            case "line" -> {
                b.put("type", "line");
                Element re = child(e.node, "reportElement");
                String color = re == null ? "" : re.getAttribute("forecolor");
                if (!color.isEmpty() && !color.equalsIgnoreCase("#000000")) b.put("color", color);
                return withVisible(b, printWhen);
            }
            case "image" -> {
                b.put("type", "image");
                String expr = textOf(child(e.node, "imageExpression"));
                String src = expr == null ? "" : template(expr, false);
                if (src.isEmpty()) return null;
                b.put("src", src);
                b.put("width", fmt(e.w) + "pt");
            }
            case "subreport" -> {
                b.put("type", "include");
                String expr = textOf(child(e.node, "subreportExpression"));
                String tpl = subreportName(expr);
                b.put("template", tpl);
                String ds = textOf(child(e.node, "dataSourceExpression"));
                if (ds != null && !ds.isBlank()) {
                    String data = bare(ds);
                    if (data != null) b.put("data", data);
                    else warnings.add("sub-relatório '" + tpl + "': dataSourceExpression não convertida: " + oneLine(ds));
                } else if (textOf(child(e.node, "connectionExpression")) != null) {
                    warnings.add("sub-relatório '" + tpl + "' usa conexão JDBC: passe os dados pelo include (data)");
                }
                Map<String, Object> ps = new LinkedHashMap<>();
                for (Element sp : children(e.node, "subreportParameter")) {
                    String pv = textOf(child(sp, "subreportParameterExpression"));
                    if (pv == null) continue;
                    String t = template(pv, false);
                    if (!t.isEmpty()) ps.put(sp.getAttribute("name"), t);
                }
                if (!ps.isEmpty()) b.put("params", ps);
                return withVisible(b, printWhen);
            }
            case "break" -> {
                b.put("type", "pageBreak");
                return b;
            }
            default -> {
                elSkipped++;
                warnings.add("elemento <" + e.kind + "> não convertido (gráfico/componente)");
                b.put("type", "text");
                b.put("value", "[" + e.kind + " do Jasper não convertido]");
                b.put("style", Map.of("color", "#c00"));
                return b;
            }
        }
        String align = align(e);
        if (align != null) b.put("align", align);
        Map<String, Object> st = style(e, true);
        if (!st.isEmpty()) b.put("style", st);
        return withVisible(b, printWhen);
    }

    private static Map<String, Object> withVisible(Map<String, Object> b, String visible) {
        if (visible != null) b.put("visible", visible);
        return b;
    }

    private String printWhen(El e) {
        Element re = child(e.node, "reportElement");
        String pw = re == null ? null : textOf(child(re, "printWhenExpression"));
        if (pw == null || pw.isBlank()) return null;
        return bare(pw);
    }

    // ------------------------------------------------------------ tabela: cabeçalhos e totais

    private List<El> findHeaderLine(List<El> cols, List<El> columnHeader, List<El> pageHeader, List<List<El>> groupHeaders) {
        List<List<El>> candidates = new ArrayList<>();
        for (List<El> band : List.of(columnHeader, pageHeader)) candidates.addAll(lines(content(band)));
        for (List<El> gh : groupHeaders) candidates.addAll(lines(content(gh)));
        List<El> best = null;
        int bestScore = 0;
        for (List<El> line : candidates) {
            List<El> statics = line.stream().filter(e -> e.kind.equals("staticText")).toList();
            Set<Integer> hit = new HashSet<>();
            for (El s : statics) {
                int i = bestColumn(cols, s);
                if (i >= 0) hit.add(i);
            }
            if (hit.size() > bestScore) {
                bestScore = hit.size();
                best = statics;
            }
        }
        return bestScore >= Math.max(1, Math.min(2, cols.size())) && bestScore * 2 >= cols.size() ? best : null;
    }

    private static int bestColumn(List<El> cols, El e) {
        int best = -1;
        double bestOv = 0;
        for (int i = 0; i < cols.size(); i++) {
            El c = cols.get(i);
            double ov = Math.min(c.right(), e.right()) - Math.max(c.x, e.x);
            if (ov > bestOv) {
                bestOv = ov;
                best = i;
            }
        }
        return bestOv >= 0.4 * Math.min(e.w, cols.get(Math.max(best, 0)).w) ? best : -1;
    }

    /**
     * Rodapé de grupo / total geral: campos $V{soma} viram {@code total} da coluna de mesma posição; texto fixo
     * vira o rótulo. Devolve true se achou algum total.
     */
    private boolean collectTotals(List<El> els, List<El> cols, List<Map<String, Object>> columns, String resetType, String group,
                                  Map<String, Object> holder, String labelKey) {
        boolean any = false;
        StringBuilder label = new StringBuilder();
        List<El> labels = new ArrayList<>();
        for (El e : els) {
            if (!e.kind.equals("textField")) {
                if (e.kind.equals("staticText")) labels.add(e);
                continue;
            }
            String expr = textOf(child(e.node, "textFieldExpression"));
            if (expr == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(?:\\(\\s*[\\w.]+\\s*\\))?\\s*\\$V\\{(\\w+)\\}\\s*$").matcher(expr);
            if (!m.find()) continue;
            Element var = variables.get(m.group(1));
            if (var == null) continue;
            String rt = var.getAttribute("resetType").isEmpty() ? "Report" : var.getAttribute("resetType");
            if (!rt.equals(resetType) || (group != null && !group.equals(var.getAttribute("resetGroup")))) continue;
            int i = bestColumn(cols, e);
            if (i < 0) continue;
            String calc = var.getAttribute("calculation");
            String kind = switch (calc) {
                case "Sum" -> "sum";
                case "Count", "DistinctCount" -> "count";
                case "Average" -> "avg";
                case "Highest" -> "max";
                case "Lowest" -> "min";
                default -> null;
            };
            if (kind == null) continue;
            String varExpr = bare(textOf(child(var, "variableExpression")));
            String colValue = (String) columns.get(i).get("value");
            if (kind.equals("count") || ("${" + varExpr + "}").equals(colValue)) columns.get(i).put("total", kind);
            else if (varExpr != null) columns.get(i).put("total", "${" + kind + "(" + varExpr + ")}");
            else continue;
            String f = format(e);
            if (f != null && !columns.get(i).containsKey("format")) columns.get(i).put("format", f);
            consumed.add(e.node);
            any = true;
        }
        if (any) {
            labels.sort(Comparator.comparingDouble(x -> x.x));
            for (El l : labels) {
                if (label.length() > 0) label.append(' ');
                label.append(text(l.node).trim());
                consumed.add(l.node);
            }
            if (label.length() > 0) holder.put(labelKey, label.toString());
        }
        return any;
    }

    // ------------------------------------------------------------ expressões

    private JavaExpr.Vars varsFor(boolean detail, boolean reportTime) {
        return name -> switch (name) {
            case "PAGE_NUMBER" -> reportTime ? "pages" : "page";
            case "PAGE_COUNT", "COLUMN_COUNT", "REPORT_COUNT" -> detail ? "index" : "count()";
            case "COLUMN_NUMBER" -> "1";
            default -> {
                if (name.endsWith("_COUNT") && !variables.containsKey(name)) yield "count()";
                yield userVar(name, detail, 0);
            }
        };
    }

    private String userVar(String name, boolean detail, int depth) {
        Element v = variables.get(name);
        if (v == null || depth > 4) return null;
        String expr = textOf(child(v, "variableExpression"));
        String calc = v.getAttribute("calculation");
        if (calc.isEmpty()) calc = "Nothing";
        String inner = null;
        if (expr != null && !expr.isBlank() && !expr.contains("$V{" + name + "}")) {
            try {
                inner = JavaExpr.code(JavaExpr.parse(expr, varsFor(detail, false)));
            } catch (JavaExpr.Unsupported e) {
                return null;
            }
        }
        return switch (calc) {
            case "Sum" -> inner == null ? null : "sum(" + inner + ")";
            case "Average" -> inner == null ? null : "avg(" + inner + ")";
            case "Highest" -> inner == null ? null : "max(" + inner + ")";
            case "Lowest" -> inner == null ? null : "min(" + inner + ")";
            case "First" -> inner == null ? null : "first(" + inner + ")";
            case "Count", "DistinctCount" -> "count()";
            default -> inner == null ? null : "(" + inner + ")";
        };
    }

    private String fieldTemplate(El e, boolean detail) {
        String expr = textOf(child(e.node, "textFieldExpression"));
        boolean reportTime = "Report".equals(e.node.getAttribute("evaluationTime"));
        return template(expr, detail, reportTime);
    }

    private String template(String javaExpr, boolean detail) {
        return template(javaExpr, detail, false);
    }

    private String template(String javaExpr, boolean detail, boolean reportTime) {
        if (javaExpr == null || javaExpr.isBlank()) return "";
        exprTotal++;
        try {
            String t = JavaExpr.template(JavaExpr.parse(javaExpr, varsFor(detail, reportTime)));
            io.github.mateusvpassos.prelo.expr.Interpolated.of(t); // valida a sintaxe
            return t;
        } catch (RuntimeException ex) {
            exprFailed++;
            warnings.add("expressão não convertida: " + oneLine(javaExpr) + " (" + ex.getMessage() + ")");
            return "[jasper: " + oneLine(javaExpr).replace("${", "$${") + "]";
        }
    }

    /** Expressão "lógica" (visible, by, data): código puro ou null se não traduziu. */
    private String bare(String javaExpr) {
        if (javaExpr == null || javaExpr.isBlank()) return null;
        exprTotal++;
        try {
            String c = JavaExpr.code(JavaExpr.parse(javaExpr, varsFor(true, false)));
            io.github.mateusvpassos.prelo.expr.Parser.parse(c);
            return c;
        } catch (RuntimeException ex) {
            exprFailed++;
            warnings.add("expressão não convertida: " + oneLine(javaExpr) + " (" + ex.getMessage() + ")");
            return null;
        }
    }

    private String subreportName(String expr) {
        if (expr == null) return "sub-relatorio";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+?)\\.(jasper|jrxml)\"").matcher(expr);
        if (m.find()) {
            String p = m.group(1);
            return p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1);
        }
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\\$P\\{(\\w+)\\}").matcher(expr);
        String param = pm.find() ? pm.group(1) : null;
        if (param != null && options.subreportParams().containsKey(param)) return options.subreportParams().get(param);
        String guess = param != null
                ? param.replaceFirst("(?i)^(path|caminho)_?", "").replaceFirst("(?i)^(sub_?report|sub_?relatorio)_?", "")
                : "sub-relatorio";
        String known = closest(guess);
        if (known != null) {
            warnings.add("sub-relatório de $P{" + param + "}: usei '" + known + "' pelo nome; confira");
            return known;
        }
        warnings.add("sub-relatório com caminho dinâmico (" + oneLine(expr) + "): ajuste o nome em 'template' (usei '" + guess + "')");
        return guess.isEmpty() ? "sub-relatorio" : Character.toLowerCase(guess.charAt(0)) + guess.substring(1);
    }

    /** Template conhecido cujo nome contém (ou está contido em) o palpite, ignorando caixa. */
    private String closest(String guess) {
        String g = guess.toLowerCase(Locale.ROOT).replaceFirst("^sub(relatorio)?", "");
        if (g.length() < 3) return null;
        String best = null;
        for (String k : options.knownTemplates()) {
            String kl = k.toLowerCase(Locale.ROOT);
            if (kl.equals(g) || kl.contains(g) || g.contains(kl.replaceFirst("^sub(relatorio)?", ""))) {
                if (best == null || k.length() < best.length()) best = k;
            }
        }
        return best;
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    // ------------------------------------------------------------ estilo e formato

    private String align(El e) {
        Element te = child(e.node, "textElement");
        String a = te == null ? "" : first(te.getAttribute("textAlignment"), te.getAttribute("hTextAlign"));
        if (a.isEmpty()) a = styleAttr(e, "hAlign", "hTextAlign");
        return switch (a) {
            case "Center" -> "center";
            case "Right" -> "right";
            case "Justified" -> "justify";
            default -> null;
        };
    }

    private Map<String, Object> style(El e, boolean includeBackground) {
        Map<String, Object> st = new LinkedHashMap<>();
        Element te = child(e.node, "textElement");
        Element font = te == null ? null : child(te, "font");
        String size = font != null ? first(font.getAttribute("size"), font.getAttribute("fontSize")) : "";
        if (size.isEmpty()) size = styleAttr(e, "fontSize");
        if (!size.isEmpty()) {
            try {
                double s = Double.parseDouble(size);
                if (Math.abs(s - 9) > 0.01) st.put("size", s == Math.rint(s) ? (Object) (int) s : (Object) s);
            } catch (NumberFormatException ignored) {
                // ignora tamanho inválido
            }
        }
        if ("true".equals(font != null ? font.getAttribute("isBold") : "") || "true".equals(styleAttr(e, "isBold"))
                || font != null && font.getAttribute("pdfFontName").toLowerCase(Locale.ROOT).contains("bold")) st.put("bold", true);
        if ("true".equals(font != null ? font.getAttribute("isItalic") : "") || "true".equals(styleAttr(e, "isItalic"))) st.put("italic", true);
        if ("true".equals(font != null ? font.getAttribute("isUnderline") : "")) st.put("underline", true);
        Element re = child(e.node, "reportElement");
        if (re != null) {
            String fc = re.getAttribute("forecolor");
            if (!fc.isEmpty() && !fc.equalsIgnoreCase("#000000")) st.put("color", fc);
            String bc = re.getAttribute("backcolor");
            if (includeBackground && "Opaque".equals(re.getAttribute("mode")) && !bc.isEmpty() && !bc.equalsIgnoreCase("#FFFFFF")) st.put("background", bc);
        }
        return st;
    }

    private String styleAttr(El e, String... names) {
        Element re = child(e.node, "reportElement");
        String sn = re == null ? "" : re.getAttribute("style");
        for (int guard = 0; !sn.isEmpty() && guard < 5; guard++) {
            Element s = styles.get(sn);
            if (s == null) break;
            for (String n : names) if (!s.getAttribute(n).isEmpty()) return s.getAttribute(n);
            sn = s.getAttribute("style");
        }
        return "";
    }

    private static String format(El e) {
        String p = e.node.getAttribute("pattern");
        if (p.isEmpty()) {
            String pe = textOf(child(e.node, "patternExpression"));
            if (pe != null && pe.trim().startsWith("\"") && pe.trim().endsWith("\"")) p = pe.trim().substring(1, pe.trim().length() - 1);
        }
        if (p.isEmpty()) return null;
        return switch (p) {
            case "#,##0.00", "#,##0.00;-#,##0.00" -> "decimal";
            case "#,##0", "#,##0;-#,##0" -> "integer";
            case "¤ #,##0.00", "¤#,##0.00", "R$ #,##0.00", "\"R$ \"#,##0.00", "¤ #,##0.00;¤ -#,##0.00" -> "currency";
            case "dd/MM/yyyy" -> "date";
            case "dd/MM/yyyy HH:mm" -> "datetime";
            case "#,##0.00 %", "#,##0.00%", "0.00%", "#,##0 %", "#,##0%" -> "percent";
            default -> p;
        };
    }

    private static String typeOf(String cls, String name) {
        String c = cls == null ? "" : cls;
        String simple = c.substring(c.lastIndexOf('.') + 1);
        return switch (simple) {
            case "String", "Character", "Object" -> "string";
            case "Integer", "Long", "Short", "Byte", "BigInteger" -> "integer";
            case "BigDecimal", "Double", "Float", "Number" -> name.toLowerCase(Locale.ROOT).matches(".*(valor|preco|preço|total|custo|saldo).*") ? "money" : "decimal";
            case "Date", "LocalDate" -> "date";
            case "Timestamp", "LocalDateTime" -> "datetime";
            case "Boolean" -> "boolean";
            case "List", "Collection", "Set", "ArrayList", "JRDataSource", "JRBeanCollectionDataSource" -> "list";
            default -> c.isEmpty() || c.startsWith("java.") ? "string" : "object";
        };
    }

    private String pct(double w) {
        double p = Math.round(w / bandWidth * 1000) / 10.0;
        return (p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%";
    }

    private static String escapeTemplate(String s) {
        return s == null ? "" : s.replace("${", "$${");
    }

    // ------------------------------------------------------------ DOM

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (tag == null || e.getTagName().equals(tag))) out.add(e);
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        List<Element> c = children(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String textOf(Element e) {
        return e == null ? null : e.getTextContent();
    }

    private static String text(Element staticText) {
        String t = textOf(child(staticText, "text"));
        return t == null ? "" : t;
    }

    private static String attr(Element e, String name, String def) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? def : v;
    }

    private static double num(Element e, String name, double def) {
        String v = e.getAttribute(name);
        if (v.isEmpty()) return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b == null ? "" : b;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= 3;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
