package io.github.mateusvpassos.relato.quarkus;

import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.ResourceResolver;
import io.github.mateusvpassos.relato.TemplateLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Disponibiliza um {@link Relato} para injeção ({@code @Inject Relato relato}), configurado por
 * {@code application.properties} com as mesmas chaves do starter Spring ({@code relato.templates}, {@code relato.cache}...).
 * Para trocar a configuração, produza o seu próprio {@code Relato} com {@code @Alternative @Priority}.
 */
@ApplicationScoped
public class RelatoProducer {

    @Produces
    @ApplicationScoped
    public Relato relato(
            @ConfigProperty(name = "relato.templates", defaultValue = "classpath:reports/") String templates,
            @ConfigProperty(name = "relato.cache", defaultValue = "true") boolean cache,
            @ConfigProperty(name = "relato.locale", defaultValue = "pt-BR") String locale,
            @ConfigProperty(name = "relato.zone", defaultValue = "America/Sao_Paulo") String zone,
            @ConfigProperty(name = "relato.date-pattern", defaultValue = "dd/MM/yyyy") String datePattern,
            @ConfigProperty(name = "relato.date-time-pattern", defaultValue = "dd/MM/yyyy HH:mm") String dateTimePattern,
            @ConfigProperty(name = "relato.chunk-rows", defaultValue = "4000") int chunkRows) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String loc = templates.trim();
        TemplateLoader loader;
        Path baseDir = null;
        if (loc.startsWith("file:")) {
            baseDir = Path.of(loc.substring("file:".length()));
            loader = TemplateLoader.directory(baseDir);
        } else {
            loader = TemplateLoader.classpath(loc, cl);
        }
        return Relato.builder()
                .templates(loader)
                .cache(cache)
                .locale(Locale.forLanguageTag(locale))
                .zone(ZoneId.of(zone))
                .datePattern(datePattern)
                .dateTimePattern(dateTimePattern)
                .resources(ResourceResolver.standard(cl, baseDir))
                .chunkRows(chunkRows)
                .build();
    }
}
