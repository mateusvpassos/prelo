package io.github.mateusvpassos.prelo.jrxml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segunda passada de uma migração em lote: o Jasper não diz o que há dentro de um campo {@code java.util.List};
 * quem sabe é o sub-relatório. Para cada {@code include} com {@code data: "lista"}, copia os campos do template
 * incluído para os subcampos de {@code lista} no relatório pai (autocomplete, aviso de campo e dados de exemplo).
 */
public final class JrxmlLinker {
    private JrxmlLinker() {}

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
    private static final String[] BANDS = {"firstPageHeader", "pageHeader", "body", "pageFooter"};

    /** @param jsonByName nome do template → conteúdo; devolve o mapa com os conteúdos atualizados */
    public static Map<String, String> link(Map<String, String> jsonByName) {
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : jsonByName.entrySet()) {
            String text = e.getValue();
            StringBuilder header = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("//")) header.append(line).append('\n');
                else break;
            }
            try {
                trees.put(e.getKey(), MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {}));
                headers.put(e.getKey(), header.toString());
            } catch (Exception ex) {
                // template ilegível: fica como está
            }
        }
        Map<String, Boolean> changed = new LinkedHashMap<>();
        for (int pass = 0; pass < 4; pass++) {
            boolean any = false;
            for (Map.Entry<String, Map<String, Object>> e : trees.entrySet()) {
                if (linkOne(e.getValue(), trees)) {
                    changed.put(e.getKey(), true);
                    any = true;
                }
            }
            if (!any) break;
        }
        Map<String, String> out = new LinkedHashMap<>(jsonByName);
        for (String name : changed.keySet()) out.put(name, headers.get(name) + JsonPretty.write(trees.get(name)) + "\n");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean linkOne(Map<String, Object> root, Map<String, Map<String, Object>> all) {
        List<Map<String, Object>> includes = new ArrayList<>();
        for (String b : BANDS) collect(root.get(b), includes);
        boolean changed = false;
        for (Map<String, Object> inc : includes) {
            Object data = inc.get("data");
            Map<String, Object> sub = all.get(String.valueOf(inc.get("template")));
            if (!(data instanceof String d) || sub == null || !d.matches("[A-Za-z_]\\w*")) continue;
            Object subFields = sub.get("fields");
            if (!(subFields instanceof List<?> sf) || sf.isEmpty()) continue;
            List<Object> fields = (List<Object>) root.computeIfAbsent("fields", k -> new ArrayList<>());
            Map<String, Object> target = null;
            for (Object f : fields) {
                if (f instanceof Map<?, ?> m && d.equals(m.get("name"))) target = (Map<String, Object>) m;
            }
            if (target == null) {
                target = new LinkedHashMap<>();
                target.put("name", d);
                fields.add(target);
            }
            Object existing = target.get("fields");
            if (existing instanceof List<?> l && !l.isEmpty()) continue;
            target.put("type", "list");
            target.put("fields", MAPPER.convertValue(sf, List.class));
            changed = true;
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, List<Map<String, Object>> out) {
        if (node instanceof List<?> l) {
            for (Object o : l) collect(o, out);
        } else if (node instanceof Map<?, ?> m) {
            if ("include".equals(m.get("type"))) out.add((Map<String, Object>) m);
            for (Object v : m.values()) {
                if (v instanceof List<?> || v instanceof Map<?, ?>) collect(v, out);
            }
        }
    }
}
