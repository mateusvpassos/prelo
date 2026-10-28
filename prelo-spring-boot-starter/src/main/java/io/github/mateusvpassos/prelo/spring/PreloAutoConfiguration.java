package io.github.mateusvpassos.prelo.spring;

import io.github.mateusvpassos.prelo.Prelo;
import io.github.mateusvpassos.prelo.ResourceResolver;
import io.github.mateusvpassos.prelo.TemplateLoader;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Registra um {@link Prelo} configurado por {@code prelo.*}. */
@AutoConfiguration
@EnableConfigurationProperties(PreloProperties.class)
public class PreloAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Prelo prelo(PreloProperties props) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String loc = props.getTemplates().trim();
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
                .cache(props.isCache())
                .locale(Locale.forLanguageTag(props.getLocale()))
                .zone(ZoneId.of(props.getZone()))
                .datePattern(props.getDatePattern())
                .dateTimePattern(props.getDateTimePattern())
                .resources(ResourceResolver.standard(cl, baseDir))
                .chunkRows(props.getChunkRows())
                .build();
    }
}
