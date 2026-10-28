package io.github.mateusvpassos.prelo.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texto com expressões: {@code "Total: ${sum(valor)}"}. Se o texto é só uma expressão ({@code "${itens}"}),
 * {@link #value} devolve o valor cru (lista, número, booleano...). Use {@code $${} para um {@code ${} literal.
 */
public final class Interpolated {
    /** Parte do texto: literal (String) ou expressão (Expr). */
    private final List<Object> parts;
    private final String source;

    private static final Map<String, Interpolated> CACHE = new ConcurrentHashMap<>();

    private Interpolated(String source, List<Object> parts) {
        this.source = source;
        this.parts = parts;
    }

    public static Interpolated of(String source) {
        if (source == null) return null;
        Interpolated cached = CACHE.get(source);
        if (cached != null) return cached;
        Interpolated i = new Interpolated(source, split(source));
        if (CACHE.size() < 10_000) CACHE.put(source, i);
        return i;
    }

    public String source() {
        return source;
    }

    public List<Object> parts() {
        return parts;
    }

    public boolean isConstant() {
        return parts.stream().allMatch(p -> p instanceof String);
    }

    /** Valor: cru se o texto é uma única expressão; senão, o texto montado. */
    public Object value(Scope scope) {
        if (parts.size() == 1 && parts.get(0) instanceof Expr e) return e.eval(scope);
        return text(scope);
    }

    public String text(Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof String s) sb.append(s);
            else {
                Object v = ((Expr) p).eval(scope);
                if (!(v instanceof PageRef)) sb.append(scope.formatter().toText(v));
            }
        }
        return sb.toString();
    }

    private static List<Object> split(String src) {
        List<Object> out = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("$${", i)) {
                lit.append("${");
                i += 3;
                continue;
            }
            if (src.startsWith("${", i)) {
                int end = findClose(src, i + 2);
                if (lit.length() > 0) {
                    out.add(lit.toString());
                    lit.setLength(0);
                }
                String body = src.substring(i + 2, end);
                try {
                    out.add(Parser.parse(body));
                } catch (ExprException e) {
                    throw new ExprException("expressão inválida ${" + body + "}: " + e.getMessage(), e);
                }
                i = end + 1;
                continue;
            }
            lit.append(src.charAt(i++));
        }
        if (lit.length() > 0 || out.isEmpty()) out.add(lit.toString());
        return List.copyOf(out);
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
        throw new ExprException("'${' sem '}' em «" + src + "»");
    }

    @Override
    public String toString() {
        return source;
    }
}
