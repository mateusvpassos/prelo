package io.github.mateusvpassos.relato.expr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Funções disponíveis nas expressões.
 *
 * <p>Agregações ({@code sum avg min max count first last join}) aceitam:
 * <ul>
 *   <li>{@code sum(valor)}: avalia {@code valor} em cada item da coleção corrente (grupo, tabela, lista ou dados);</li>
 *   <li>{@code sum(itens)}: se {@code itens} é uma coleção visível no escopo, agrega seus elementos;</li>
 *   <li>{@code sum(itens, valor)}: avalia {@code valor} em cada item de {@code itens};</li>
 *   <li>{@code count()}: tamanho da coleção corrente.</li>
 * </ul>
 */
public final class Functions {
    private Functions() {}

    private interface Fn {
        Object apply(List<Expr> args, Scope scope);
    }

    private static final Map<String, Fn> FNS = new TreeMap<>();

    static {
        agg("sum", Functions::sum);
        agg("avg", items -> {
            BigDecimal s = BigDecimal.ZERO;
            int n = 0;
            for (Object v : items) {
                if (v == null) continue;
                s = s.add(Values.toNumber(v));
                n++;
            }
            return n == 0 ? null : Values.normalize(s.divide(BigDecimal.valueOf(n), MathContext.DECIMAL64));
        });
        agg("min", items -> items.stream().filter(v -> v != null).min(Values::compare).orElse(null));
        agg("max", items -> items.stream().filter(v -> v != null).max(Values::compare).orElse(null));
        agg("first", items -> items.isEmpty() ? null : items.get(0));
        agg("last", items -> items.isEmpty() ? null : items.get(items.size() - 1));
        FNS.put("count", (args, scope) -> {
            if (args.isEmpty()) return BigDecimal.valueOf(Values.toList(scope.aggregateItems()).size());
            List<Object> vals = aggregate(args, scope);
            return BigDecimal.valueOf(vals.stream().filter(v -> v != null).count());
        });
        FNS.put("join", (args, scope) -> {
            if (args.isEmpty()) throw new ExprException("join(valor, ', ') ou join(lista, valor, ', ')");
            String sep = ", ";
            List<Expr> a = args;
            if (args.size() >= 2 && args.get(args.size() - 1) instanceof Expr.Literal l && l.value() instanceof String s) {
                sep = s;
                a = args.subList(0, args.size() - 1);
            }
            StringJoiner j = new StringJoiner(sep);
            for (Object v : aggregate(a, scope)) if (v != null) j.add(scope.formatter().toText(v));
            return j.toString();
        });

        fn("format", (a, s) -> {
            need(a, 2, "format(valor, 'padrão')");
            return s.formatter().format(a.get(0), Values.str(a.get(1)));
        });
        fn("upper", (a, s) -> a.get(0) == null ? null : s.formatter().toText(a.get(0)).toUpperCase(s.formatter().locale()));
        fn("lower", (a, s) -> a.get(0) == null ? null : s.formatter().toText(a.get(0)).toLowerCase(s.formatter().locale()));
        fn("trim", (a, s) -> a.get(0) == null ? null : s.formatter().toText(a.get(0)).trim());
        fn("text", (a, s) -> s.formatter().toText(a.get(0)));
        fn("number", (a, s) -> Values.toNumber(a.get(0)));
        fn("len", (a, s) -> {
            Object v = a.get(0);
            if (v == null) return BigDecimal.ZERO;
            if (v instanceof CharSequence cs) return BigDecimal.valueOf(cs.length());
            return BigDecimal.valueOf(Values.toList(v).size());
        });
        fn("isEmpty", (a, s) -> !Values.truthy(a.get(0)) && !(a.get(0) instanceof Number) && !(a.get(0) instanceof Boolean));
        fn("coalesce", (a, s) -> {
            for (Object v : a) if (v != null && !(v instanceof CharSequence cs && cs.isEmpty())) return v;
            return null;
        });
        fn("contains", (a, s) -> {
            need(a, 2, "contains(texto, parte)");
            if (Values.isCollection(a.get(0))) return Values.toList(a.get(0)).stream().anyMatch(v -> Values.equal(v, a.get(1)));
            return a.get(0) != null && Values.str(a.get(0)).contains(Values.str(a.get(1)));
        });
        fn("replace", (a, s) -> {
            need(a, 3, "replace(texto, de, para)");
            return a.get(0) == null ? null : Values.str(a.get(0)).replace(Values.str(a.get(1)), Values.str(a.get(2)));
        });
        fn("substr", (a, s) -> {
            if (a.get(0) == null) return null;
            String str = Values.str(a.get(0));
            int from = Math.min(Math.max(0, Values.toNumber(a.get(1)).intValue()), str.length());
            int to = a.size() > 2 ? Math.min(Math.max(from, Values.toNumber(a.get(2)).intValue()), str.length()) : str.length();
            return str.substring(from, to);
        });
        fn("round", (a, s) -> {
            BigDecimal n = Values.toNumber(a.get(0));
            int scale = a.size() > 1 ? Values.toNumber(a.get(1)).intValue() : 0;
            return n == null ? null : n.setScale(scale, RoundingMode.HALF_UP);
        });
        fn("abs", (a, s) -> {
            BigDecimal n = Values.toNumber(a.get(0));
            return n == null ? null : n.abs();
        });
        fn("if", (a, s) -> {
            need(a, 2, "if(condição, valor, senão)");
            return Values.truthy(a.get(0)) ? a.get(1) : a.size() > 2 ? a.get(2) : null;
        });
        fn("values", (a, s) -> a.get(0) instanceof Map<?, ?> m ? new ArrayList<>(m.values()) : Values.toList(a.get(0)));
        fn("keys", (a, s) -> a.get(0) instanceof Map<?, ?> m ? new ArrayList<>(m.keySet()) : List.of());
        fn("pix", (a, s) -> BrCodes.pix(Values.str(a.get(0)), a.size() > 1 ? Values.toNumber(a.get(1)) : null,
                a.size() > 2 ? Values.str(a.get(2)) : null, a.size() > 3 ? Values.str(a.get(3)) : null,
                a.size() > 4 ? Values.str(a.get(4)) : null, a.size() > 5 ? Values.str(a.get(5)) : null));
        fn("boleto", (a, s) -> BrCodes.boletoBarcode(Values.str(a.get(0))));
        fn("today", (a, s) -> LocalDate.now(s.formatter().zone()));
        fn("now", (a, s) -> LocalDateTime.now(s.formatter().zone()));
    }

    public static boolean exists(String name) {
        return FNS.containsKey(name);
    }

    public static String names() {
        return String.join(", ", FNS.keySet());
    }

    static Object call(String name, List<Expr> args, Scope scope) {
        Fn f = FNS.get(name);
        if (f == null) throw new ExprException("função desconhecida '" + name + "'");
        return f.apply(args, scope);
    }

    private static void fn(String name, java.util.function.BiFunction<List<Object>, Scope, Object> f) {
        FNS.put(name, (args, scope) -> {
            List<Object> vals = new ArrayList<>(args.size());
            for (Expr e : args) vals.add(e.eval(scope));
            if (vals.isEmpty() && !name.equals("today") && !name.equals("now") && !name.equals("coalesce")) {
                throw new ExprException(name + "() precisa de argumento");
            }
            return f.apply(vals, scope);
        });
    }

    private static void agg(String name, java.util.function.Function<List<Object>, Object> f) {
        FNS.put(name, (args, scope) -> {
            if (args.isEmpty()) throw new ExprException(name + "(valor) ou " + name + "(lista, valor)");
            return f.apply(aggregate(args, scope));
        });
    }

    /** Valores para uma agregação, conforme a forma de chamada (ver doc da classe). */
    private static List<Object> aggregate(List<Expr> args, Scope scope) {
        List<?> items;
        Expr valueExpr;
        if (args.size() >= 2) {
            items = Values.toList(args.get(0).eval(scope));
            valueExpr = args.get(1);
        } else {
            // sum(itens) com 'itens' coleção visível aqui: agrega os próprios elementos
            List<?> current = scope.aggregateItems();
            Object direct;
            try {
                direct = args.get(0).eval(scope);
            } catch (ExprException e) {
                if (current == null) throw e;
                direct = null;
            }
            if (Values.isCollection(direct) || current == null) return new ArrayList<>(Values.toList(direct));
            items = current;
            valueExpr = args.get(0);
        }
        List<Object> out = new ArrayList<>(items.size());
        for (Object it : items) out.add(valueExpr.eval(scope.withRow(it)));
        return out;
    }

    private static Object sum(List<Object> items) {
        BigDecimal s = BigDecimal.ZERO;
        for (Object v : items) {
            BigDecimal n = Values.toNumber(v);
            if (n != null) s = s.add(n);
        }
        return s;
    }

    private static void need(List<Object> a, int n, String usage) {
        if (a.size() < n) throw new ExprException("uso: " + usage);
    }
}
