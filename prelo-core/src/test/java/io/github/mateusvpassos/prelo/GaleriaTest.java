package io.github.mateusvpassos.prelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mateusvpassos.prelo.check.FieldCheck;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Renderiza os exemplos de {@code examples/} com o sample de cada um. Com {@code -Dgaleria=true} grava a
 * primeira página como PNG em {@code docs/galeria} (imagens do GALERIA.md).
 */
class GaleriaTest {
    private static final Path EXAMPLES = Path.of("..", "examples");
    private final Prelo prelo = Prelo.create();

    @ParameterizedTest
    @ValueSource(strings = {"etiquetas", "crachas", "contrato", "cobranca", "painel", "pedidos"})
    void exemploRenderiza(String nome) throws Exception {
        String json = Files.readString(EXAMPLES.resolve(nome + ".report.json"));
        assertEquals(List.of(), FieldCheck.check(json), nome);
        ReportTemplate t = Templates.parse(json);
        SampleData s = SampleData.parse(Files.readString(EXAMPLES.resolve(nome + ".sample.json")), t);
        byte[] pdf = prelo.report(t).data(s.data()).params(s.params()).pdf();
        String txt = PreloTest.text(pdf);
        switch (nome) {
            case "etiquetas" -> assertEquals(20, count(txt, "cód. P0"), txt);
            case "crachas" -> assertTrue(txt.contains("Ana Souza") && txt.contains("PALESTRANTE"), txt);
            case "contrato" -> assertTrue(txt.contains("R$\u00a0400,00") && txt.contains("07 de outubro de 2026"), txt);
            case "cobranca" -> assertTrue(txt.contains("R$\u00a0487,50") && txt.contains("br.gov.bcb.pix"), txt);
            case "painel" -> assertTrue(txt.contains("Região") && txt.contains("Total"), txt);
            default -> { }
        }
        PreloTest.save("galeria-" + nome + ".pdf", pdf);
        if (Boolean.getBoolean("galeria")) {
            Path dir = Path.of("..", "docs", "galeria");
            Files.createDirectories(dir);
            try (PDDocument d = Loader.loadPDF(pdf)) {
                javax.imageio.ImageIO.write(new PDFRenderer(d).renderImageWithDPI(0, 70), "png", dir.resolve(nome + ".png").toFile());
            }
        }
    }

    private static int count(String s, String sub) {
        int c = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) c++;
        return c;
    }
}
