package io.github.mateusvpassos.prelo;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.mateusvpassos.prelo.model.FieldDef;
import io.github.mateusvpassos.prelo.model.ParamDef;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Dados de exemplo para preview: lidos de um {@code *.sample.json} ou inventados a partir de {@code fields}
 * (valor plausível pelo nome e tipo de cada campo).
 *
 * <p>Formato do sample: {@code {"params": {...}, "data": [...] }} ({@code data} pode ser objeto único).
 */
public final class SampleData {
    private final Map<String, Object> params;
    private final Object data;

    public SampleData(Map<String, Object> params, Object data) {
        this.params = params;
        this.data = data;
    }

    public Map<String, Object> params() {
        return params;
    }

    public Object data() {
        return data;
    }

    /** Lê um sample JSON; datas em texto ISO ({@code 2026-09-30}) viram {@link LocalDate}, tipadas pelos {@code fields}. */
    public static SampleData parse(String json, ReportTemplate t) {
        Map<String, Object> root;
        try {
            root = Templates.MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new ReportException("sample inválido: " + e.getMessage(), e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = root.get("params") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Object data = root.get("data");
        for (ParamDef d : t.parameters()) {
            if (p.containsKey(d.name())) p.put(d.name(), coerce(p.get(d.name()), d.type()));
        }
        return new SampleData(p, coerceData(data, t.fields()));
    }

    /** JSON no formato do {@code *.sample.json} (datas em ISO). */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("params", plain(params));
        root.put("data", plain(data));
        try {
            return Templates.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new ReportException("falha ao serializar sample: " + e.getMessage(), e);
        }
    }

    private static Object plain(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(SampleData::plain).toList();
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        return v;
    }

    public static SampleData generate(ReportTemplate t, int rows) {
        Random r = new Random(7);
        Map<String, Object> p = new LinkedHashMap<>();
        for (ParamDef d : t.parameters()) {
            if (d.sample() != null) p.put(d.name(), coerce(d.sample(), d.type()));
            else if (d.defaultValue() == null && !looksLikeResource(d.name())) p.put(d.name(), fake(d.name(), d.type(), 0, r));
            else if (d.defaultValue() == null) p.put(d.name(), null);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        if (!t.fields().isEmpty()) {
            for (int i = 0; i < rows; i++) data.add(row(t.fields(), i, r));
        }
        return new SampleData(p, data);
    }

    private static Map<String, Object> row(List<FieldDef> fields, int i, Random r) {
        // objeto sem subcampos conhecidos: propriedades ausentes valem null (não derruba o preview)
        Map<String, Object> m = fields.isEmpty() ? new io.github.mateusvpassos.prelo.expr.Values.LenientMap() : new LinkedHashMap<>();
        for (FieldDef f : fields) {
            String type = f.type() == null ? "string" : f.type();
            if (f.sample() != null) m.put(f.name(), coerce(f.sample(), type));
            else if ("list".equals(type)) {
                List<Object> l = new ArrayList<>();
                int n = 2 + r.nextInt(4);
                for (int j = 0; j < n; j++) l.add(row(f.fields(), j, r));
                m.put(f.name(), l);
            } else if ("object".equals(type)) m.put(f.name(), row(f.fields(), i, r));
            else m.put(f.name(), fake(f.name(), type, i, r));
        }
        return m;
    }

    private static final String[] NOMES = {"Ana Souza", "Bruno Lima", "Carla Mendes", "Diego Rocha", "Elisa Martins", "Fábio Costa", "Gabriela Alves", "Henrique Dias"};
    private static final String[] EMPRESAS = {"Padaria Pão Quente", "Mercado Bom Preço", "Farmácia Saúde", "Auto Peças Curitiba", "Livraria Saber", "Construtora Horizonte"};
    private static final String[] CIDADES = {"Curitiba", "Londrina", "Maringá", "Ponta Grossa", "Cascavel", "Foz do Iguaçu"};
    private static final String[] PRODUTOS = {"Caderno", "Caneta azul", "Mochila", "Calculadora", "Grampeador", "Resma A4"};
    private static final String[] STATUS = {"Ativo", "Pendente", "Concluído", "Cancelado"};
    private static final String[] REGIOES = {"Sul", "Sudeste", "Norte", "Nordeste", "Centro-Oeste"};

    static Object fake(String name, String type, int i, Random r) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String t = type == null ? "string" : type;
        switch (t) {
            case "integer":
                if (n.contains("ano")) return 2020 + r.nextInt(7);
                return n.startsWith("id") || n.contains("numero") || n.contains("codigo") ? 1000 + i : 1 + r.nextInt(20);
            case "number":
            case "decimal":
                return BigDecimal.valueOf(r.nextInt(100_000), 2);
            case "money":
                return BigDecimal.valueOf(1_000 + r.nextInt(500_000), 2);
            case "date":
                return LocalDate.of(2026, 1 + r.nextInt(12), 1 + r.nextInt(28));
            case "datetime":
                return LocalDateTime.of(LocalDate.of(2026, 1 + r.nextInt(12), 1 + r.nextInt(28)), LocalTime.of(8 + r.nextInt(10), r.nextInt(60)));
            case "boolean":
                return r.nextBoolean();
            case "text":
                return "Texto de exemplo para " + name + ", com mais de uma linha quando o espaço for curto.";
            default:
                break;
        }
        if (n.contains("cpf")) return String.format("%011d", Math.abs(r.nextLong()) % 100_000_000_000L);
        if (n.contains("cnpj")) return String.format("%014d", Math.abs(r.nextLong()) % 100_000_000_000_000L);
        if (n.contains("cep")) return String.format("%08d", 80_000_000 + r.nextInt(9_999_999));
        if (n.contains("email")) return "contato" + (i + 1) + "@exemplo.com.br";
        if (n.contains("telefone") || n.contains("fone") || n.contains("celular")) return "(41) 9" + (8000 + r.nextInt(1999)) + "-" + (1000 + r.nextInt(8999));
        if (n.equals("uf") || n.contains("estado")) return "PR";
        if (n.contains("cidade") || n.contains("municipio")) return pick(CIDADES, r);
        if (n.contains("regiao")) return pick(REGIOES, r);
        if (n.contains("status") || n.contains("situacao")) return pick(STATUS, r);
        if (n.contains("produto") || n.contains("item")) return pick(PRODUTOS, r);
        if (n.contains("empresa") || n.contains("cliente") || n.contains("fornecedor") || n.contains("razao")) return pick(EMPRESAS, r);
        if (n.contains("nome") || n.contains("vendedor") || n.contains("responsavel") || n.contains("funcionario")) return pick(NOMES, r);
        if (n.contains("endereco") || n.contains("logradouro")) return "Rua das Flores, " + (10 + r.nextInt(990));
        if (n.contains("descricao") || n.contains("obs")) return "Descrição do item " + (i + 1);
        if (n.contains("codigo") || n.startsWith("cod") || n.equals("id")) return String.valueOf(1000 + i);
        return capital(name) + " " + (i + 1);
    }

    /** Parâmetros de caminho/imagem (logo, foto, path...) não recebem valor inventado. */
    private static boolean looksLikeResource(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.startsWith("path") || n.contains("icon") || n.contains("logo") || n.contains("foto") || n.contains("imagem")
                || n.contains("image") || n.contains("brasao") || n.contains("brasão") || n.endsWith("_dir");
    }

    private static String pick(String[] a, Random r) {
        return a[r.nextInt(a.length)];
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "Valor" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @SuppressWarnings("unchecked")
    private static Object coerceData(Object data, List<FieldDef> fields) {
        if (data instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(coerceData(o, fields));
            return out;
        }
        if (data instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) m);
            for (FieldDef f : fields) {
                if (!out.containsKey(f.name())) continue;
                Object v = out.get(f.name());
                out.put(f.name(), "list".equals(f.type()) || "object".equals(f.type()) ? coerceData(v, f.fields()) : coerce(v, f.type()));
            }
            // campos não declarados: datas ISO viram LocalDate
            out.replaceAll((k, v) -> v instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}") ? coerce(s, "date") : v);
            return out;
        }
        return data;
    }

    static Object coerce(Object v, String type) {
        if (v == null || type == null) return v;
        try {
            return switch (type) {
                case "date" -> v instanceof String s ? LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s) : v;
                case "datetime" -> v instanceof String s ? LocalDateTime.parse(s) : v;
                case "number", "decimal", "money" -> v instanceof Number || v instanceof String ? new BigDecimal(v.toString()) : v;
                case "integer" -> v instanceof Number num ? num.longValue() : v instanceof String s ? Long.parseLong(s.trim()) : v;
                case "boolean" -> v instanceof String s ? Boolean.parseBoolean(s) : v;
                default -> v;
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            return v;
        }
    }
}
