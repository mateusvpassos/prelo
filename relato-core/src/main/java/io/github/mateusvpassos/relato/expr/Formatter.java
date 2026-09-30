package io.github.mateusvpassos.relato.expr;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Converte valores em texto.
 *
 * <p>Formatos nomeados: {@code currency number integer decimal percent date datetime time cpf cnpj cep upper lower};
 * {@code 'Sim|Não'} para booleanos; qualquer outro texto é padrão de {@link DecimalFormat} (números)
 * ou de {@link DateTimeFormatter} (datas).
 */
public final class Formatter {
    private final Locale locale;
    private final ZoneId zone;
    private final String datePattern;
    private final String dateTimePattern;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public Formatter(Locale locale, ZoneId zone, String datePattern, String dateTimePattern) {
        this.locale = locale;
        this.zone = zone;
        this.datePattern = datePattern;
        this.dateTimePattern = dateTimePattern;
    }

    public static Formatter brazil() {
        return new Formatter(Locale.forLanguageTag("pt-BR"), ZoneId.of("America/Sao_Paulo"), "dd/MM/yyyy", "dd/MM/yyyy HH:mm");
    }

    public Locale locale() {
        return locale;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Texto padrão, sem formato explícito. */
    public String toText(Object v) {
        if (v == null) return "";
        if (v instanceof CharSequence s) return s.toString();
        if (v instanceof Boolean b) return bool(b);
        if (v instanceof Number n) return plainNumber(n);
        if (v instanceof Enum<?> e) return e.toString();
        if (v instanceof LocalDate d) return pattern(datePattern).format(d);
        if (v instanceof LocalDateTime d) return pattern(dateTimePattern).format(d);
        if (v instanceof LocalTime t) return pattern("HH:mm").format(t);
        if (v instanceof java.sql.Date d) return pattern(datePattern).format(d.toLocalDate());
        if (v instanceof java.util.Date || v instanceof Instant || v instanceof ZonedDateTime || v instanceof OffsetDateTime) {
            ZonedDateTime z = zoned(v);
            return pattern(z.toLocalTime().equals(LocalTime.MIDNIGHT) ? datePattern : dateTimePattern).format(z);
        }
        if (v instanceof Collection<?> c) return c.stream().map(this::toText).collect(Collectors.joining(", "));
        if (v instanceof Object[] arr) return java.util.Arrays.stream(arr).map(this::toText).collect(Collectors.joining(", "));
        return v.toString();
    }

    public String format(Object v, String spec) {
        if (spec == null || spec.isBlank()) return toText(v);
        if (v == null) return "";
        String s = spec.trim();
        switch (s) {
            case "upper": return toText(v).toUpperCase(locale);
            case "lower": return toText(v).toLowerCase(locale);
            case "cpf": return mask(v, "###.###.###-##", 11);
            case "cnpj": return mask(v, "##.###.###/####-##", 14);
            case "cpfCnpj": {
                String d = digits(v);
                return d.length() > 11 ? mask(v, "##.###.###/####-##", 14) : mask(v, "###.###.###-##", 11);
            }
            case "cep": return mask(v, "#####-###", 8);
            default: break;
        }
        if (s.contains("|")) {
            String[] parts = s.split("\\|", 2);
            return Values.truthy(v) ? parts[0] : parts[1];
        }
        if (v instanceof TemporalAccessor || v instanceof java.util.Date) return formatDate(v, s);
        if (v instanceof Number || v instanceof CharSequence) {
            BigDecimal n;
            try {
                n = Values.toNumber(v);
            } catch (ExprException e) {
                return toText(v);
            }
            if (n == null) return "";
            return switch (s) {
                case "currency", "money" -> NumberFormat.getCurrencyInstance(locale).format(n);
                case "number" -> decimal("#,##0.##").format(n);
                case "integer" -> decimal("#,##0").format(n);
                case "decimal" -> decimal("#,##0.00").format(n);
                case "percent" -> decimal("#,##0.##%").format(n);
                case "date", "datetime", "time" -> toText(v);
                default -> decimal(s).format(n);
            };
        }
        return toText(v);
    }

    private String formatDate(Object v, String spec) {
        String p = switch (spec) {
            case "date" -> datePattern;
            case "datetime" -> dateTimePattern;
            case "time" -> "HH:mm";
            default -> spec;
        };
        DateTimeFormatter f = pattern(p);
        Temporal t = v instanceof java.sql.Date d ? d.toLocalDate()
                : v instanceof java.util.Date || v instanceof Instant ? zoned(v)
                : v instanceof Temporal tt ? tt : null;
        if (t == null) return toText(v);
        try {
            return f.format(t);
        } catch (UnsupportedTemporalTypeException e) {
            if (t instanceof LocalDate d) return f.format(d.atStartOfDay());
            throw new ExprException("formato '" + spec + "' não se aplica a " + v.getClass().getSimpleName(), e);
        }
    }

    private ZonedDateTime zoned(Object v) {
        if (v instanceof ZonedDateTime z) return z;
        if (v instanceof OffsetDateTime o) return o.atZoneSameInstant(zone);
        if (v instanceof Instant i) return i.atZone(zone);
        return Instant.ofEpochMilli(((java.util.Date) v).getTime()).atZone(zone);
    }

    private String plainNumber(Number n) {
        BigDecimal b = Values.toNumber(n);
        if (b == null) return "";
        if (n instanceof Double || n instanceof Float) b = Values.normalize(b);
        String s = b.toPlainString();
        char sep = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        return sep == '.' ? s : s.replace('.', sep);
    }

    private String bool(boolean b) {
        return "pt".equals(locale.getLanguage()) ? (b ? "Sim" : "Não") : (b ? "Yes" : "No");
    }

    private DecimalFormat decimal(String pattern) {
        DecimalFormat f = (DecimalFormat) cache.computeIfAbsent("#" + pattern, k -> {
            try {
                DecimalFormat df = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale));
                df.setParseBigDecimal(true);
                return df;
            } catch (IllegalArgumentException e) {
                throw new ExprException("formato numérico inválido '" + pattern + "'", e);
            }
        });
        return (DecimalFormat) f.clone();
    }

    private DateTimeFormatter pattern(String p) {
        return (DateTimeFormatter) cache.computeIfAbsent("@" + p, k -> {
            try {
                return DateTimeFormatter.ofPattern(p, locale);
            } catch (IllegalArgumentException e) {
                throw new ExprException("formato de data inválido '" + p + "'", e);
            }
        });
    }

    private static String digits(Object v) {
        String raw = v instanceof Number n ? Values.toNumber(n).toBigInteger().toString() : v.toString();
        return raw.replaceAll("\\D", "");
    }

    private static String mask(Object v, String mask, int size) {
        String d = digits(v);
        if (d.length() > size) return v.toString();
        d = "0".repeat(size - d.length()) + d;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (char c : mask.toCharArray()) sb.append(c == '#' ? d.charAt(i++) : c);
        return sb.toString();
    }
}
