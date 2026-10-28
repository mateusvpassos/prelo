package io.github.mateusvpassos.prelo;

import io.github.mateusvpassos.prelo.render.ReportRenderer;
import java.util.List;

/**
 * Fontes extras descobertas via {@link java.util.ServiceLoader} (ex.: o módulo {@code prelo-fonts}).
 * Registre em {@code META-INF/services/io.github.mateusvpassos.prelo.FontSource}.
 */
public interface FontSource {
    List<ReportRenderer.Font> fonts();

    /** Família usada como fonte padrão do documento, ou null para manter a padrão (Helvetica). */
    default String defaultFamily() {
        return null;
    }
}
