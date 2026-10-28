package io.github.mateusvpassos.prelo.jrxml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** JSON no estilo dos templates: objetos curtos numa linha, o resto quebrado e indentado. */
final class JsonPretty {
    private JsonPretty() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WIDTH = 110;

    static String write(Object v) {
        return pretty(v, "");
    }

    private static String pretty(Object v, String indent) {
        if (!(v instanceof Map<?, ?>) && !(v instanceof List<?>)) return scalar(v);
        if (!indent.isEmpty()) {
            String one = inline(v);
            if (indent.length() + one.length() <= WIDTH) return one;
        }
        String inner = indent + "  ";
        StringBuilder sb = new StringBuilder();
        if (v instanceof List<?> l) {
            if (l.isEmpty()) return "[]";
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                sb.append(inner).append(pretty(l.get(i), inner)).append(i < l.size() - 1 ? ",\n" : "\n");
            }
            return sb.append(indent).append(']').toString();
        }
        Map<?, ?> m = (Map<?, ?>) v;
        if (m.isEmpty()) return "{}";
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            sb.append(inner).append(scalar(String.valueOf(e.getKey()))).append(": ").append(pretty(e.getValue(), inner))
                    .append(++i < m.size() ? ",\n" : "\n");
        }
        return sb.append(indent).append('}').toString();
    }

    private static String inline(Object v) {
        if (v instanceof List<?> l) {
            if (l.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[ ");
            for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? ", " : "").append(inline(l.get(i)));
            return sb.append(" ]").toString();
        }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{ ");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(i++ > 0 ? ", " : "").append(scalar(String.valueOf(e.getKey()))).append(": ").append(inline(e.getValue()));
            }
            return sb.append(" }").toString();
        }
        return scalar(v);
    }

    private static String scalar(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
