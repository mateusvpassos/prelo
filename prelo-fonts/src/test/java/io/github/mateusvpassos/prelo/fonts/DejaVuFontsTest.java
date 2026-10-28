package io.github.mateusvpassos.prelo.fonts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mateusvpassos.prelo.Prelo;
import io.github.mateusvpassos.prelo.Templates;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DejaVuFontsTest {
    @Test
    void simbolosUnicodeSaemNoPdf() throws Exception {
        var t = Templates.parse("""
                {"body":[{"type":"text","value":"${s}"},{"type":"text","value":"negrito ✓","style":{"bold":true}}]}
                """);
        String s = "Situação ✓ ★ → ← Ω α β Δ ≤ ≥ ∑ € £ ¥ ₹ Привет";
        byte[] pdf = Prelo.create().report(t).data(Map.of("s", s)).pdf();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String txt = new PDFTextStripper().getText(d);
            assertTrue(txt.contains(s), txt);
            assertTrue(txt.contains("negrito ✓"), txt);
            assertTrue(d.getPage(0).getResources().getFontNames().iterator().hasNext());
        }
    }
}
