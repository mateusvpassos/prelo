package io.github.mateusvpassos.relato.spring;

import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.ResourceResolver;
import io.github.mateusvpassos.relato.TemplateLoader;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Registra um {@link Relato} configurado por {@code relato.*}. */
@AutoConfiguration
@EnableConfigurationProperties(RelatoProperties.class)
public class RelatoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Relato relato(RelatoProperties props) {
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
        return Relato.builder()
                .templates(loader)
                .cache(props.isCache())
                .locale(Locale.forLanguageTag(props.getLocale()))
                .zone(ZoneId.of(props.getZone()))
                .datePattern(props.getDatePattern())
                .dateTimePattern(props.getDateTimePattern())
                .resources(ResourceResolver.standard(cl, baseDir))
                .build();
    }
}
