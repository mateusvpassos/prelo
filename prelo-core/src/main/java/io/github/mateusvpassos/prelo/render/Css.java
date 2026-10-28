package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.ReportException;
import java.util.Map;
import java.util.Set;

/** Converte o {@code style} dos elementos em CSS. Aceita texto CSS ou objeto (camelCase ou kebab-case). */
final class Css {
    private Css() {}

    private static final Set<String> UNITLESS = Set.of("font-weight", "line-height", "opacity", "z-index", "orphans", "widows", "flex");

    static String of(Object style) {
        if (style == null) return "";
        if (style instanceof String s) return s.trim();
        if (!(style instanceof Map<?, ?> map)) throw new ReportException("style deve ser texto CSS ou objeto, não " + style.getClass().getSimpleName());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) continue;
            switch (key) {
                case "bold" -> append(sb, "font-weight", Boolean.TRUE.equals(v) ? "bold" : "normal");
                case "italic" -> append(sb, "font-style", Boolean.TRUE.equals(v) ? "italic" : "normal");
                case "underline" -> append(sb, "text-decoration", Boolean.TRUE.equals(v) ? "underline" : "none");
                case "uppercase" -> append(sb, "text-transform", Boolean.TRUE.equals(v) ? "uppercase" : "none");
                case "size" -> append(sb, "font-size", length("font-size", v));
                case "align" -> append(sb, "text-align", String.valueOf(v));
                case "valign" -> append(sb, "vertical-align", String.valueOf(v));
                case "font" -> append(sb, "font-family", String.valueOf(v));
                case "background" -> append(sb, "background-color", String.valueOf(v));
                case "border" -> append(sb, "border", Boolean.TRUE.equals(v) ? "0.5pt solid #999" : String.valueOf(v));
                default -> {
                    String prop = kebab(key);
                    append(sb, prop, length(prop, v));
                }
            }
        }
        return sb.toString();
    }

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            String t = p.trim();
            sb.append(t);
            if (!t.endsWith(";")) sb.append(';');
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String prop, String value) {
        sb.append(prop).append(':').append(value).append(';');
    }

    /** Números viram pt, exceto propriedades sem unidade. */
    private static String length(String prop, Object v) {
        if (v instanceof Number n) {
            if (UNITLESS.contains(prop)) return n.toString();
            return n + "pt";
        }
        return String.valueOf(v);
    }

    private static String kebab(String key) {
        if (key.indexOf('-') >= 0) return key;
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('-').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
