package io.github.mateusvpassos.prelo.expr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Acesso a propriedades (Map, record, getter, campo público) e operações com valores. */
public final class Values {
    private Values() {}

    private static final ClassValue<Map<String, Function<Object, Object>>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Function<Object, Object>> computeValue(Class<?> type) {
            return accessorsOf(type);
        }
    };

    /** Mapa em que chave ausente vale null (parâmetros do relatório). */
    public static final class LenientMap extends java.util.LinkedHashMap<String, Object> {}

    public static boolean hasProperty(Object obj, String name) {
        if (obj instanceof Map<?, ?> m) return m.containsKey(name);
        if (obj instanceof Collection<?> || obj.getClass().isArray()) return "size".equals(name) || "length".equals(name);
        return ACCESSORS.get(obj.getClass()).containsKey(name);
    }

    /** Valor da propriedade; nulo-seguro ({@code null.x} = null). Propriedade inexistente é erro. */
    public static Object property(Object obj, String name) {
        if (obj == null) return null;
        if (obj instanceof LenientMap m) return m.get(name);
        if (obj instanceof Map<?, ?> m) {
            if (!m.containsKey(name)) throw new ExprException("propriedade '" + name + "' não existe no mapa (chaves: " + m.keySet() + ")");
            return m.get(name);
        }
        if (obj instanceof Collection<?> c && ("size".equals(name) || "length".equals(name))) return c.size();
        if (obj.getClass().isArray() && ("size".equals(name) || "length".equals(name))) return java.lang.reflect.Array.getLength(obj);
        Function<Object, Object> acc = ACCESSORS.get(obj.getClass()).get(name);
        if (acc == null) {
            throw new ExprException("propriedade '" + name + "' não existe em " + obj.getClass().getSimpleName()
                    + " (disponíveis: " + String.join(", ", propertyNames(obj)) + ")");
        }
        return acc.apply(obj);
    }

    public static Collection<String> propertyNames(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            return keys;
        }
        if (obj instanceof Collection<?> || obj.getClass().isArray()) return List.of();
        return ACCESSORS.get(obj.getClass()).keySet();
    }

    public static Object index(Object obj, Object key) {
        if (obj == null || key == null) return null;
        if (obj instanceof Map<?, ?> m) return m.get(key instanceof BigDecimal b ? toKey(m, b) : key);
        if (obj instanceof List<?> l) {
            int i = toNumber(key).intValue();
            return i >= 0 && i < l.size() ? l.get(i) : null;
        }
        if (obj.getClass().isArray()) {
            int i = toNumber(key).intValue();
            return i >= 0 && i < java.lang.reflect.Array.getLength(obj) ? java.lang.reflect.Array.get(obj, i) : null;
        }
        return property(obj, String.valueOf(key));
    }

    private static Object toKey(Map<?, ?> m, BigDecimal b) {
        for (Object k : m.keySet()) {
            if (k instanceof Number n && toNumber(n).compareTo(b) == 0) return k;
        }
        return b;
    }

    /** Converte coleções, arrays, iteráveis e streams em lista; null vira lista vazia; objeto único vira lista de 1. */
    public static List<?> toList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l;
        if (v instanceof Collection<?> c) return new ArrayList<>(c);
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            it.forEach(out::add);
            return out;
        }
        if (v instanceof java.util.stream.BaseStream<?, ?> s) {
            List<Object> out = new ArrayList<>();
            s.iterator().forEachRemaining(out::add);
            return out;
        }
        if (v instanceof Object[] arr) return Arrays.asList(arr);
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(java.lang.reflect.Array.get(v, i));
            return out;
        }
        if (v instanceof Map<?, ?> m) return List.of(m);
        return List.of(v);
    }

    public static boolean isCollection(Object v) {
        return v instanceof Iterable<?> || (v != null && v.getClass().isArray() && !(v instanceof byte[]));
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return toNumber(n).signum() != 0;
        if (v instanceof CharSequence s) return !s.isEmpty() && !"false".contentEquals(s);
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    public static boolean isNumber(Object v) {
        return v instanceof Number;
    }

    public static BigDecimal toNumber(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof BigInteger b) return new BigDecimal(b);
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return BigDecimal.valueOf(d);
        }
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ExprException("'" + s + "' não é um número");
        }
    }

    /** Número "limpo": inteiros sem casas decimais inúteis. */
    public static BigDecimal normalize(BigDecimal b) {
        if (b == null) return null;
        if (b.scale() > 0) {
            BigDecimal s = b.stripTrailingZeros();
            if (s.scale() <= 0) return s.setScale(0);
        }
        return b;
    }

    public static Object add(Object a, Object b) {
        if (a instanceof CharSequence || b instanceof CharSequence) return str(a) + str(b);
        if (a == null || b == null) return null;
        BigDecimal x = toNumber(a), y = toNumber(b);
        return x == null || y == null ? null : x.add(y);
    }

    public static Object arith(char op, Object a, Object b) {
        if (a == null || b == null) return null;
        BigDecimal x = toNumber(a), y = toNumber(b);
        if (x == null || y == null) return null;
        return switch (op) {
            case '-' -> x.subtract(y);
            case '*' -> x.multiply(y);
            case '/' -> {
                if (y.signum() == 0) yield null;
                yield normalize(x.divide(y, MathContext.DECIMAL64));
            }
            case '%' -> y.signum() == 0 ? null : x.remainder(y);
            default -> throw new IllegalArgumentException(String.valueOf(op));
        };
    }

    public static boolean equal(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            BigDecimal x = toNumber(a), y = toNumber(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        if (a instanceof Enum<?> e && b instanceof CharSequence s) return e.name().contentEquals(s);
        if (b instanceof Enum<?> e && a instanceof CharSequence s) return e.name().contentEquals(s);
        if (a instanceof CharSequence x && b instanceof CharSequence y) return x.toString().contentEquals(y);
        if (a instanceof Number && b instanceof CharSequence || a instanceof CharSequence && b instanceof Number) {
            try {
                BigDecimal x = toNumber(a), y = toNumber(b);
                return x != null && y != null && x.compareTo(y) == 0;
            } catch (ExprException e) {
                return false;
            }
        }
        return Objects.equals(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            BigDecimal x = toNumber(a), y = toNumber(b);
            if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
            return x.compareTo(y);
        }
        if (a instanceof java.util.Date x && b instanceof java.util.Date y) return Long.compare(x.getTime(), y.getTime());
        if (a instanceof Comparable ca && a.getClass().isInstance(b)) return ca.compareTo(b);
        if (b instanceof Comparable cb && b.getClass().isInstance(a)) return -cb.compareTo(a);
        return str(a).compareTo(str(b));
    }

    static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static Map<String, Function<Object, Object>> accessorsOf(Class<?> type) {
        Map<String, Function<Object, Object>> out = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                Method m = rc.getAccessor();
                m.trySetAccessible();
                out.put(rc.getName(), invoker(m));
            }
        }
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            String prop = null;
            if (n.startsWith("get") && n.length() > 3 && m.getReturnType() != void.class) prop = decap(n.substring(3));
            else if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) prop = decap(n.substring(2));
            if (prop != null && !out.containsKey(prop)) {
                Method pm = publicVersion(m);
                out.put(prop, invoker(pm));
            }
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || out.containsKey(f.getName())) continue;
                if (!f.trySetAccessible()) continue;
                out.put(f.getName(), obj -> {
                    try {
                        return f.get(obj);
                    } catch (IllegalAccessException e) {
                        throw new ExprException("sem acesso a " + f, e);
                    }
                });
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Métodos de classes não públicas (ex.: implementações internas) precisam ser chamados pela interface pública. */
    private static Method publicVersion(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
        for (Class<?> itf : m.getDeclaringClass().getInterfaces()) {
            try {
                return itf.getMethod(m.getName());
            } catch (NoSuchMethodException ignored) {
                // tenta a próxima
            }
        }
        m.trySetAccessible();
        return m;
    }

    private static Function<Object, Object> invoker(Method m) {
        return obj -> {
            try {
                return m.invoke(obj);
            } catch (IllegalAccessException e) {
                throw new ExprException("sem acesso a " + m, e);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new ExprException(m.getDeclaringClass().getSimpleName() + "." + m.getName() + "() lançou " + e.getCause(), e.getCause());
            }
        };
    }

    private static String decap(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
