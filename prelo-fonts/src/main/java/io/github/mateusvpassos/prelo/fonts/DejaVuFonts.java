package io.github.mateusvpassos.prelo.fonts;

import io.github.mateusvpassos.prelo.FontSource;
import io.github.mateusvpassos.prelo.render.ReportRenderer;
import java.util.List;

/**
 * DejaVu Sans (Unicode amplo: acentos, símbolos, grego, cirílico, setas, ✓ ★ €...). Basta ter este jar no
 * classpath: vira a fonte padrão dos relatórios. Licença livre (Bitstream Vera), ver DejaVu-LICENSE.txt.
 */
public final class DejaVuFonts implements FontSource {
    private static final String FAMILY = "DejaVu Sans";

    @Override
    public List<ReportRenderer.Font> fonts() {
        return List.of(
                font("DejaVuSans.ttf", FAMILY, 400, false),
                font("DejaVuSans-Bold.ttf", FAMILY, 700, false),
                font("DejaVuSans-Oblique.ttf", FAMILY, 400, true),
                font("DejaVuSans-BoldOblique.ttf", FAMILY, 700, true),
                font("DejaVuSansMono.ttf", "DejaVu Sans Mono", 400, false));
    }

    @Override
    public String defaultFamily() {
        return FAMILY;
    }

    private static ReportRenderer.Font font(String file, String family, int weight, boolean italic) {
        return new ReportRenderer.Font(() -> DejaVuFonts.class.getResourceAsStream(file), family, weight, italic);
    }
}
