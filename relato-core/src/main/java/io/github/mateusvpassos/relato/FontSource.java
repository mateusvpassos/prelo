package io.github.mateusvpassos.relato;

import io.github.mateusvpassos.relato.render.ReportRenderer;
import java.util.List;

/**
 * Fontes extras descobertas via {@link java.util.ServiceLoader} (ex.: o módulo {@code relato-fonts}).
 * Registre em {@code META-INF/services/io.github.mateusvpassos.relato.FontSource}.
 */
public interface FontSource {
    List<ReportRenderer.Font> fonts();

    /** Família usada como fonte padrão do documento, ou null para manter a padrão (Helvetica). */
    default String defaultFamily() {
        return null;
    }
}
