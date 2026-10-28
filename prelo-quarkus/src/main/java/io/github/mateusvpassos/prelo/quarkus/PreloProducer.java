package io.github.mateusvpassos.prelo.quarkus;

import io.github.mateusvpassos.prelo.Prelo;
import io.github.mateusvpassos.prelo.ResourceResolver;
import io.github.mateusvpassos.prelo.TemplateLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Disponibiliza um {@link Prelo} para injeção ({@code @Inject Prelo prelo}), configurado por
 * {@code application.properties} com as mesmas chaves do starter Spring ({@code prelo.templates}, {@code prelo.cache}...).
 * Para trocar a configuração, produza o seu próprio {@code Prelo} com {@code @Alternative @Priority}.
 */
@ApplicationScoped
public class PreloProducer {

    @Produces
    @ApplicationScoped
    public Prelo prelo(
            @ConfigProperty(name = "prelo.templates", defaultValue = "classpath:reports/") String templates,
            @ConfigProperty(name = "prelo.cache", defaultValue = "true") boolean cache,
            @ConfigProperty(name = "prelo.locale", defaultValue = "pt-BR") String locale,
            @ConfigProperty(name = "prelo.zone", defaultValue = "America/Sao_Paulo") String zone,
            @ConfigProperty(name = "prelo.date-pattern", defaultValue = "dd/MM/yyyy") String datePattern,
            @ConfigProperty(name = "prelo.date-time-pattern", defaultValue = "dd/MM/yyyy HH:mm") String dateTimePattern,
            @ConfigProperty(name = "prelo.chunk-rows", defaultValue = "4000") int chunkRows) {
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
        return Prelo.builder()
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
