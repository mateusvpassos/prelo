package io.github.mateusvpassos.relato;

import io.github.mateusvpassos.relato.expr.Formatter;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import io.github.mateusvpassos.relato.render.ReportRenderer;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Engine de relatórios. Thread-safe; crie uma vez e reutilize.
 *
 * <pre>{@code
 * Relato relato = Relato.builder().templates(TemplateLoader.classpath("reports/")).build();
 * byte[] pdf = relato.report("vendas").data(vendas).param("periodo", "09/2026").pdf();
 * }</pre>
 */
public final class Relato {
    private final TemplateLoader loader;
    private final boolean cache;
    private final Map<String, ReportTemplate> templates = new ConcurrentHashMap<>();
    private final ReportRenderer renderer;

    private Relato(Builder b) {
        this.loader = b.loader;
        this.cache = b.cache;
        Formatter formatter = new Formatter(b.locale, b.zone, b.datePattern, b.dateTimePattern);
        ResourceResolver res = b.resources != null ? b.resources : ResourceResolver.standard(b.classLoader, null);
        this.renderer = new ReportRenderer(formatter, res, b.fonts);
    }

    /** Padrões: templates em {@code classpath:reports/}, pt-BR, America/Sao_Paulo. */
    public static Relato create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ReportRequest report(String name) {
        return new ReportRequest(template(name), renderer);
    }

    public ReportRequest report(ReportTemplate template) {
        return new ReportRequest(Objects.requireNonNull(template, "template"), renderer);
    }

    public ReportTemplate template(String name) {
        Objects.requireNonNull(name, "name");
        if (!cache) return loader.load(name);
        return templates.computeIfAbsent(name, loader::load);
    }

    /** Descarta templates em cache (ex.: depois de editar em desenvolvimento). */
    public void clearCache() {
        templates.clear();
    }

    public static final class Builder {
        private TemplateLoader loader = TemplateLoader.classpath("reports/");
        private boolean cache = true;
        private Locale locale = Locale.forLanguageTag("pt-BR");
        private ZoneId zone = ZoneId.of("America/Sao_Paulo");
        private String datePattern = "dd/MM/yyyy";
        private String dateTimePattern = "dd/MM/yyyy HH:mm";
        private ResourceResolver resources;
        private ClassLoader classLoader;
        private final List<ReportRenderer.Font> fonts = new ArrayList<>();

        private Builder() {}

        public Builder templates(TemplateLoader loader) {
            this.loader = Objects.requireNonNull(loader);
            return this;
        }

        /** Guarda templates já lidos (padrão true). Desligue em desenvolvimento para ver edições sem reiniciar. */
        public Builder cache(boolean cache) {
            this.cache = cache;
            return this;
        }

        public Builder locale(Locale locale) {
            this.locale = Objects.requireNonNull(locale);
            return this;
        }

        public Builder zone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone);
            return this;
        }

        public Builder datePattern(String pattern) {
            this.datePattern = Objects.requireNonNull(pattern);
            return this;
        }

        public Builder dateTimePattern(String pattern) {
            this.dateTimePattern = Objects.requireNonNull(pattern);
            return this;
        }

        /** Como resolver {@code src} das imagens (padrão: {@code classpath:} e caminhos de arquivo). */
        public Builder resources(ResourceResolver resources) {
            this.resources = resources;
            return this;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        /** Registra uma fonte TTF para usar em {@code style: {font: "Família"}}. */
        public Builder font(Supplier<InputStream> ttf, String family, int weight, boolean italic) {
            fonts.add(new ReportRenderer.Font(ttf, family, weight, italic));
            return this;
        }

        public Relato build() {
            return new Relato(this);
        }
    }
}
