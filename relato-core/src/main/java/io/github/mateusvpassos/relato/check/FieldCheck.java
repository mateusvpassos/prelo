package io.github.mateusvpassos.relato.check;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mateusvpassos.relato.expr.Expr;
import io.github.mateusvpassos.relato.expr.ExprException;
import io.github.mateusvpassos.relato.expr.Functions;
import io.github.mateusvpassos.relato.expr.Parser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Confere as expressões de um template contra os {@code fields} declarados: campo inexistente, propriedade
 * inexistente em objeto, fonte de tabela/lista que não é lista e erro de sintaxe. Também informa os nomes
 * visíveis num ponto do template (autocomplete).
 *
 * <p>Trabalha sobre a árvore JSON (tolerante a propriedades desconhecidas). Sem {@code fields} declarados,
 * não acusa campos (só sintaxe).
 */
public final class FieldCheck {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);

    private static final Set<String> AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "first", "last", "join");
    private static final Set<String> BARE_PROPS = Set.of("visible", "source", "sortBy", "by", "data");
    private static final List<String> RESERVED = List.of("rows", "data", "page", "pages", "param", "params", "index", "item");
    private static final String[] BANDS = {"firstPageHeader", "pageHeader", "body", "pageFooter"};

    /**
     * @param path   caminho JSON até a propriedade de texto, ex.: {@code ["body", 3, "columns", 2, "value"]}
     * @param start  início do trecho com problema dentro do texto (-1 = texto todo)
     * @param end    fim (exclusivo)
     */
    public record Problem(List<Object> path, int start, int end, String message) {
        public String pathString() {
            return FieldCheck.pathString(path);
        }
    }

    /** Nome sugerido no autocomplete. {@code kind}: field, var, param, function. */
    public record Name(String name, String type, String kind) {}

    // ------------------------------------------------------------ nós de tipo

    static final class FNode {
        final String name;
        final String type;
        /** null = desconhecido (aceita qualquer propriedade). */
        final Map<String, FNode> children;

        FNode(String name, String type, Map<String, FNode> children) {
            this.name = name;
            this.type = type;
            this.children = children;
        }

        boolean isList() {
            return "list".equals(type);
        }

        boolean isAny() {
            return "any".equals(type);
        }
    }

    private static final FNode ANY = new FNode("?", "any", null);
    private static final FNode SCALAR = new FNode("?", "scalar", null);

    private static Map<String, FNode> fieldsOf(JsonNode arr) {
        Map<String, FNode> out = new LinkedHashMap<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode f : arr) {
            String name = f.path("name").asText("");
            if (name.isEmpty()) continue;
            String type = f.path("type").asText("string");
            Map<String, FNode> kids = null;
            if ("list".equals(type) || "object".equals(type)) {
                JsonNode sub = f.get("fields");
                kids = sub != null && sub.isArray() && sub.size() > 0 ? fieldsOf(sub) : null;
            }
            out.put(name, new FNode(name, type, kids));
        }
        return out;
    }

    // ------------------------------------------------------------ escopo

    private static final class Ctx {
        final Ctx parent;
        /** Campos da linha; null = nenhum; OPEN = desconhecidos (aceita tudo). */
        final Map<String, FNode> fields;
        final boolean open;
        final Map<String, FNode> vars;
        /** Campos usados pelas agregações de 1 argumento (itens da coleção corrente). */
        final Map<String, FNode> agg;
        final boolean aggOpen;

        Ctx(Ctx parent, Map<String, FNode> fields, boolean open, Map<String, FNode> vars, Map<String, FNode> agg, boolean aggOpen) {
            this.parent = parent;
            this.fields = fields;
            this.open = open;
            this.vars = vars;
            this.agg = agg;
            this.aggOpen = aggOpen;
        }

        FNode lookup(String name) {
            for (Ctx c = this; c != null; c = c.parent) {
                if (c.fields != null && c.fields.containsKey(name)) return c.fields.get(name);
                if (c.vars != null && c.vars.containsKey(name)) return c.vars.get(name);
            }
            if ("page".equals(name) || "pages".equals(name)) return SCALAR;
            return null;
        }

        boolean anyOpen() {
            for (Ctx c = this; c != null; c = c.parent) if (c.open) return true;
            return false;
        }

        Ctx aggCtx() {
            for (Ctx c = this; c != null; c = c.parent) {
                if (c.agg != null || c.aggOpen) return new Ctx(this, c.agg, c.aggOpen, null, null, false);
            }
            return this;
        }

        List<Name> names() {
            Map<String, Name> out = new LinkedHashMap<>();
            for (Ctx c = this; c != null; c = c.parent) {
                String kind = c.parent == null ? "param" : "var";
                if (c.fields != null) c.fields.forEach((k, v) -> out.putIfAbsent(k, new Name(k, v.type, "field")));
                if (c.vars != null) c.vars.forEach((k, v) -> out.putIfAbsent(k, new Name(k, v.type, kind)));
            }
            out.putIfAbsent("page", new Name("page", "integer", "var"));
            out.putIfAbsent("pages", new Name("pages", "integer", "var"));
            return new ArrayList<>(out.values());
        }
    }

    // ------------------------------------------------------------ API

    public static List<Problem> check(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        return check(root);
    }

    public static List<Problem> check(JsonNode root) {
        Walker w = new Walker(root, true);
        w.run();
        return w.problems;
    }

    /**
     * Nomes para completar em {@code path} (caminho da propriedade de texto), dado o texto da expressão até o
     * cursor (ex.: {@code "cliente."} lista as propriedades de cliente; {@code "cli"} lista os nomes do escopo).
     */
    public static List<Name> complete(JsonNode root, List<Object> path, String exprBeforeCaret) {
        Walker w = new Walker(root, false);
        w.run();
        Ctx ctx = w.ctxAt(path);
        String prefix = exprBeforeCaret == null ? "" : exprBeforeCaret;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*\\.\\s*[A-Za-z_$]?[\\w$]*$").matcher(prefix);
        if (m.find()) {
            FNode target = resolveChain(m.group(1).replaceAll("\\s", ""), ctx);
            if (target == null || target.children == null) return List.of();
            List<Name> out = new ArrayList<>();
            target.children.forEach((k, v) -> out.add(new Name(k, v.type, "field")));
            if (target.isList()) {
                out.add(new Name("size", "integer", "var"));
            }
            return out;
        }
        List<Name> out = new ArrayList<>(ctx.names());
        for (String f : Functions.names().split(",\\s*")) out.add(new Name(f, "function", "function"));
        return out;
    }

    public static JsonNode parse(String json) throws java.io.IOException {
        return MAPPER.readTree(json);
    }

    public static boolean isBareProperty(String prop) {
        return BARE_PROPS.contains(prop);
    }

    static String pathString(List<Object> path) {
        StringBuilder sb = new StringBuilder();
        for (Object o : path) {
            if (o instanceof Integer i) sb.append('[').append(i).append(']');
            else sb.append(sb.length() == 0 ? "" : ".").append(o);
        }
        return sb.toString();
    }

    private static FNode resolveChain(String chain, Ctx ctx) {
        String[] parts = chain.split("\\.");
        FNode cur = ctx.lookup(parts[0]);
        if (cur == null) cur = ctx.aggCtx().lookup(parts[0]);
        for (int i = 1; cur != null && i < parts.length; i++) {
            cur = cur.children == null ? null : cur.children.get(parts[i]);
        }
        return cur;
    }

    // ------------------------------------------------------------ percurso

    private static final class Walker {
        final JsonNode root;
        final boolean report;
        final boolean hasFields;
        final List<Problem> problems = new ArrayList<>();
        final Map<String, Ctx> ctxByPath = new HashMap<>();
        final Ctx rootCtx;

        Walker(JsonNode root, boolean report) {
            this.root = root == null ? MAPPER.createObjectNode() : root;
            this.report = report;
            Map<String, FNode> fields = fieldsOf(this.root.get("fields"));
            this.hasFields = !fields.isEmpty();

            Map<String, FNode> params = new LinkedHashMap<>();
            JsonNode ps = this.root.get("parameters");
            if (ps != null && ps.isArray()) {
                for (JsonNode p : ps) {
                    String n = p.path("name").asText("");
                    if (!n.isEmpty()) params.put(n, new FNode(n, p.path("type").asText("string"), null));
                }
            }
            Map<String, FNode> pvars = new LinkedHashMap<>(params);
            FNode paramObj = new FNode("param", "object", params.isEmpty() ? null : params);
            pvars.put("param", paramObj);
            pvars.put("params", paramObj);
            Ctx paramCtx = new Ctx(null, null, false, pvars, null, false);

            Map<String, FNode> dvars = new LinkedHashMap<>();
            dvars.put("rows", new FNode("rows", "list", hasFields ? fields : null));
            dvars.put("data", ANY);
            this.rootCtx = new Ctx(paramCtx, fields, !hasFields, dvars, fields, !hasFields);
        }

        void run() {
            JsonNode ps = root.get("parameters");
            if (ps != null && ps.isArray()) {
                for (int i = 0; i < ps.size(); i++) {
                    String n = ps.get(i).path("name").asText("");
                    if (RESERVED.contains(n)) {
                        problem(List.of("parameters", i, "name"), -1, -1, "'" + n + "' é nome reservado (" + String.join(", ", RESERVED) + "); escolha outro");
                    }
                }
            }
            JsonNode title = root.get("title");
            if (title != null && title.isTextual()) template(title.asText(), List.of("title"), rootCtx);
            JsonNode wm = root.get("watermark");
            if (wm != null && wm.isObject()) {
                template(wm, "text", List.of("watermark"), rootCtx);
                template(wm, "src", List.of("watermark"), rootCtx);
                bare(wm, "visible", List.of("watermark"), rootCtx);
            }
            JsonNode md = root.get("metadata");
            if (md != null && md.isObject()) {
                for (String k : List.of("author", "subject", "keywords", "creator")) template(md, k, List.of("metadata"), rootCtx);
            }
            JsonNode sec = root.get("security");
            if (sec != null && sec.isObject()) {
                template(sec, "userPassword", List.of("security"), rootCtx);
                template(sec, "ownerPassword", List.of("security"), rootCtx);
            }
            for (String band : BANDS) elements(root.get(band), List.of(band), rootCtx);
        }

        Ctx ctxAt(List<Object> path) {
            for (int n = path.size(); n >= 0; n--) {
                Ctx c = ctxByPath.get(pathString(path.subList(0, n)));
                if (c != null) return c;
            }
            return rootCtx;
        }

        void elements(JsonNode arr, List<Object> path, Ctx ctx) {
            ctxByPath.put(pathString(path), ctx);
            if (arr == null || !arr.isArray()) return;
            for (int i = 0; i < arr.size(); i++) element(arr.get(i), append(path, i), ctx);
        }

        void element(JsonNode el, List<Object> path, Ctx ctx) {
            if (el == null || !el.isObject()) return;
            ctxByPath.put(pathString(path), ctx);
            bare(el, "visible", path, ctx);
            switch (el.path("type").asText("")) {
                case "text", "html", "markdown" -> template(el, "value", path, ctx);
                case "image" -> template(el, "src", path, ctx);
                case "barcode" -> template(el, "value", path, ctx);
                case "include" -> {
                    bare(el, "data", path, ctx);
                    JsonNode ps = el.get("params");
                    if (ps != null && ps.isObject()) ps.fieldNames().forEachRemaining(k -> template(ps, k, append(path, "params"), ctx));
                }
                case "fields" -> {
                    JsonNode items = el.get("items");
                    if (items != null && items.isArray()) {
                        for (int i = 0; i < items.size(); i++) {
                            List<Object> ip = append(path, "items", i);
                            ctxByPath.put(pathString(ip), ctx);
                            template(items.get(i), "label", ip, ctx);
                            template(items.get(i), "value", ip, ctx);
                        }
                    }
                }
                case "row" -> {
                    JsonNode cols = el.get("columns");
                    if (cols != null && cols.isArray()) {
                        for (int i = 0; i < cols.size(); i++) elements(cols.get(i).get("content"), append(path, "columns", i, "content"), ctx);
                    }
                }
                case "box" -> elements(el.get("content"), append(path, "content"), ctx);
                case "table" -> table(el, path, ctx);
                case "list" -> list(el, path, ctx);
                case "chart" -> chart(el, path, ctx);
                case "labels" -> {
                    Ctx item = itemCtx(el, path, ctx, "item");
                    sortBy(el, path, item);
                    template(el, "copies", path, item);
                    elements(el.get("content"), append(path, "content"), item);
                }
                case "crosstab" -> {
                    Ctx item = itemCtx(el, path, ctx, null);
                    for (String k : List.of("rows", "columns", "value")) template(el, k, path, item);
                    template(el, "rowHeader", path, ctx);
                    template(el, "totalLabel", path, ctx);
                }
                default -> { }
            }
        }

        void table(JsonNode el, List<Object> path, Ctx ctx) {
            Ctx item = itemCtx(el, path, ctx, null);
            template(el, "emptyText", path, ctx);
            template(el, "totalLabel", path, item);
            sortBy(el, path, item);
            JsonNode groups = el.get("groups");
            if (groups != null && groups.isArray()) {
                for (int i = 0; i < groups.size(); i++) {
                    List<Object> gp = append(path, "groups", i);
                    ctxByPath.put(pathString(gp), item);
                    bare(groups.get(i), "by", gp, item);
                    template(groups.get(i), "header", gp, item);
                    template(groups.get(i), "footerLabel", gp, item);
                }
            }
            JsonNode cols = el.get("columns");
            if (cols != null && cols.isArray()) {
                for (int i = 0; i < cols.size(); i++) {
                    JsonNode c = cols.get(i);
                    List<Object> cp = append(path, "columns", i);
                    ctxByPath.put(pathString(cp), item);
                    template(c, "header", cp, ctx);
                    template(c, "value", cp, item);
                    JsonNode total = c.get("total");
                    if (total != null && total.isTextual() && total.asText().contains("${")) template(c, "total", cp, item);
                }
            }
        }

        void chart(JsonNode el, List<Object> path, Ctx ctx) {
            Ctx item = itemCtx(el, path, ctx, null);
            sortBy(el, path, item);
            template(el, "title", path, ctx);
            template(el, "label", path, item);
            template(el, "value", path, item);
            JsonNode series = el.get("series");
            if (series != null && series.isArray()) {
                for (int i = 0; i < series.size(); i++) {
                    List<Object> sp = append(path, "series", i);
                    ctxByPath.put(pathString(sp), item);
                    template(series.get(i), "name", sp, ctx);
                    template(series.get(i), "value", sp, item);
                }
            }
        }

        void list(JsonNode el, List<Object> path, Ctx ctx) {
            String as = el.path("as").asText("");
            Ctx item = itemCtx(el, path, ctx, as.isEmpty() ? "item" : as);
            sortBy(el, path, item);
            template(el, "emptyText", path, ctx);
            Ctx around = new Ctx(ctx, null, false, null, item.fields, item.open);
            elements(el.get("header"), append(path, "header"), around);
            elements(el.get("content"), append(path, "content"), item);
            elements(el.get("footer"), append(path, "footer"), around);
        }

        /** Escopo dos itens de uma tabela/lista, a partir de {@code source}. */
        Ctx itemCtx(JsonNode el, List<Object> path, Ctx ctx, String alias) {
            JsonNode srcNode = el.get("source");
            String src = srcNode != null && srcNode.isTextual() && !srcNode.asText().isBlank() ? srcNode.asText() : "rows";
            FNode type;
            List<Problem> tmp = new ArrayList<>();
            type = expression(stripDollar(src), ctx, tmp, append(path, "source"), -1, -1);
            if (srcNode != null) record(tmp);
            Map<String, FNode> fields = null;
            boolean open = true;
            if (type != null && type.isList()) {
                fields = type.children;
                open = type.children == null;
            } else if (type != null && !type.isAny() && hasFields && tmp.isEmpty()) {
                problem(append(path, "source"), -1, -1, "'" + src + "' não é uma lista (tipo " + type.type + ")");
            }
            Map<String, FNode> vars = new LinkedHashMap<>();
            vars.put("index", new FNode("index", "integer", null));
            vars.put("groupKey", SCALAR);
            if (alias != null) vars.put(alias, new FNode(alias, "object", fields));
            return new Ctx(ctx, fields, open, vars, fields, open);
        }

        void sortBy(JsonNode el, List<Object> path, Ctx ctx) {
            ctxByPath.put(pathString(append(path, "sortBy")), ctx);
            JsonNode s = el.get("sortBy");
            if (s == null || !s.isTextual()) return;
            for (String key : splitTopLevel(s.asText())) {
                String k = key.startsWith("-") ? key.substring(1) : key;
                expression(stripDollar(k), ctx, problems, append(path, "sortBy"), -1, -1);
            }
        }

        void bare(JsonNode obj, String prop, List<Object> path, Ctx ctx) {
            JsonNode v = obj.get(prop);
            if (v == null || !v.isTextual() || v.asText().isBlank()) return;
            List<Object> p = append(path, prop);
            ctxByPath.put(pathString(p), ctx);
            if (v.asText().contains("${")) template(v.asText(), p, ctx);
            else expression(v.asText(), ctx, problems, p, -1, -1);
        }

        void template(JsonNode obj, String prop, List<Object> path, Ctx ctx) {
            if (obj == null) return;
            JsonNode v = obj.get(prop);
            if (v == null || !v.isTextual()) return;
            List<Object> p = append(path, prop);
            ctxByPath.put(pathString(p), ctx);
            template(v.asText(), p, ctx);
        }

        void template(String s, List<Object> path, Ctx ctx) {
            int i = 0;
            while (i < s.length()) {
                if (s.startsWith("$${", i)) {
                    i += 3;
                    continue;
                }
                if (!s.startsWith("${", i)) {
                    i++;
                    continue;
                }
                int close = findClose(s, i + 2);
                if (close < 0) {
                    problem(path, i, s.length(), "'${' sem '}'");
                    return;
                }
                expression(s.substring(i + 2, close), ctx, problems, path, i, close + 1);
                i = close + 1;
            }
        }

        /** Confere uma expressão; devolve o tipo do resultado (ou null se não resolvido). */
        FNode expression(String src, Ctx ctx, List<Problem> out, List<Object> path, int start, int end) {
            Expr e;
            try {
                e = Parser.parse(src);
            } catch (ExprException ex) {
                out.add(new Problem(path, start, end, ex.getMessage()));
                return null;
            }
            return new ExprCheck(ctx, out, path, start, end).visit(e);
        }

        void problem(List<Object> path, int start, int end, String msg) {
            if (report) problems.add(new Problem(path, start, end, msg));
        }

        void record(List<Problem> ps) {
            if (report) problems.addAll(ps);
        }

        final class ExprCheck {
            final Ctx ctx;
            final List<Problem> out;
            final List<Object> path;
            final int start, end;

            ExprCheck(Ctx ctx, List<Problem> out, List<Object> path, int start, int end) {
                this.ctx = ctx;
                this.out = out;
                this.path = path;
                this.start = start;
                this.end = end;
            }

            void add(String msg) {
                if (report && hasFields) out.add(new Problem(path, start, end, msg));
            }

            FNode visit(Expr e) {
                if (e instanceof Expr.Literal) return SCALAR;
                if (e instanceof Expr.Ident id) {
                    FNode n = ctx.lookup(id.name());
                    if (n != null) return n;
                    if (ctx.anyOpen()) return ANY;
                    add("campo '" + id.name() + "' não existe" + available(ctx.names()));
                    return null;
                }
                if (e instanceof Expr.Prop p) {
                    FNode t = visit(p.target());
                    if (t == null || t.children == null) return t == null ? null : ANY;
                    if (t.isList() && ("size".equals(p.name()) || "length".equals(p.name()))) return SCALAR;
                    if (t.isList()) {
                        add("'" + t.name + "' é uma lista; use uma tabela/lista com source \"" + t.name + "\" ou sum(" + t.name + ", " + p.name() + ")");
                        return null;
                    }
                    FNode c = t.children.get(p.name());
                    if (c == null) {
                        List<Name> names = new ArrayList<>();
                        t.children.forEach((k, v) -> names.add(new Name(k, v.type, "field")));
                        add("'" + p.name() + "' não existe em " + t.name + available(names));
                    }
                    return c;
                }
                if (e instanceof Expr.Index ix) {
                    visit(ix.target());
                    visit(ix.key());
                    return ANY;
                }
                if (e instanceof Expr.Call call) return call(call);
                if (e instanceof Expr.Unary u) {
                    visit(u.operand());
                    return SCALAR;
                }
                if (e instanceof Expr.Binary b) {
                    visit(b.left());
                    visit(b.right());
                    return SCALAR;
                }
                if (e instanceof Expr.Conditional c) {
                    visit(c.cond());
                    FNode a = visit(c.then());
                    visit(c.otherwise());
                    return a;
                }
                return ANY;
            }

            FNode call(Expr.Call call) {
                List<Expr> args = call.args();
                if (!AGGREGATES.contains(call.name())) {
                    for (Expr a : args) visit(a);
                    return SCALAR;
                }
                if (args.size() >= 2) {
                    FNode coll = visit(args.get(0));
                    Ctx inner = coll != null && coll.isList() && coll.children != null
                            ? new Ctx(ctx, coll.children, false, null, null, false)
                            : new Ctx(ctx, null, true, null, null, false);
                    new ExprCheck(inner, out, path, start, end).visit(args.get(1));
                    for (int i = 2; i < args.size(); i++) visit(args.get(i));
                    return SCALAR;
                }
                if (args.isEmpty()) return SCALAR;
                // sum(itens) com 'itens' lista visível, ou sum(valor) sobre os itens correntes
                List<Problem> tmp = new ArrayList<>();
                FNode direct = new ExprCheck(ctx, tmp, path, start, end).visit(args.get(0));
                if (tmp.isEmpty() && direct != null && direct.isList()) return SCALAR;
                new ExprCheck(ctx.aggCtx(), out, path, start, end).visit(args.get(0));
                return SCALAR;
            }
        }
    }

    private static String available(Collection<Name> names) {
        List<String> ns = new ArrayList<>();
        for (Name n : names) if (!"var".equals(n.kind()) || !Set.of("page", "pages", "groupKey").contains(n.name())) ns.add(n.name());
        if (ns.isEmpty()) return "";
        return " (disponíveis: " + String.join(", ", ns.size() > 12 ? ns.subList(0, 12) : ns) + (ns.size() > 12 ? ", ..." : "") + ")";
    }

    private static String stripDollar(String s) {
        String t = s.trim();
        if (t.startsWith("${") && t.endsWith("}")) return t.substring(2, t.length() - 1);
        return t;
    }

    private static List<Object> append(List<Object> path, Object... more) {
        List<Object> p = new ArrayList<>(path.size() + more.length);
        p.addAll(path);
        for (Object m : more) p.add(m);
        return p;
    }

    private static int findClose(String src, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
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
}
