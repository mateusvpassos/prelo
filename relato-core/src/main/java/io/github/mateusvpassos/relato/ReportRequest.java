package io.github.mateusvpassos.relato;

import io.github.mateusvpassos.relato.model.ReportTemplate;
import io.github.mateusvpassos.relato.render.ReportRenderer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Um relatório a gerar: dados + parâmetros + formato. Não reutilize entre threads. */
public final class ReportRequest {
    private final ReportTemplate template;
    private final ReportRenderer renderer;
    private Object data;
    private final Map<String, Object> params = new LinkedHashMap<>();

    ReportRequest(ReportTemplate template, ReportRenderer renderer) {
        this.template = template;
        this.renderer = renderer;
    }

    /**
     * Dados: uma coleção (vira {@code rows}, fonte padrão das tabelas) ou um objeto
     * (propriedades acessíveis direto, ex.: {@code ${pedido.numero}} ou {@code ${numero}}).
     */
    public ReportRequest data(Object data) {
        this.data = data;
        return this;
    }

    public ReportRequest param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public ReportRequest params(Map<String, ?> values) {
        if (values != null) params.putAll(values);
        return this;
    }

    public ReportTemplate template() {
        return template;
    }

    public byte[] pdf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        pdf(out);
        return out.toByteArray();
    }

    public void pdf(OutputStream out) {
        renderer.pdf(template, data, params, out);
    }

    public String html() {
        return renderer.html(template, data, params);
    }

    public byte[] xlsx() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        xlsx(out);
        return out.toByteArray();
    }

    public void xlsx(OutputStream out) {
        renderer.xlsx(template, data, params, out);
    }

    /** Primeira tabela em CSV (separador ';', UTF-8 com BOM). */
    public byte[] csv() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        renderer.csv(template, data, params, out);
        return out.toByteArray();
    }

    public byte[] render(Format format) {
        return switch (format) {
            case PDF -> pdf();
            case HTML -> html().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case XLSX -> xlsx();
            case CSV -> csv();
        };
    }

    public void render(Format format, OutputStream out) {
        switch (format) {
            case PDF -> pdf(out);
            case HTML -> {
                try {
                    out.write(html().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            case XLSX -> xlsx(out);
            case CSV -> renderer.csv(template, data, params, out);
        }
    }
}
