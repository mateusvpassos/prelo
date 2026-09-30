package io.github.mateusvpassos.relato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayoutTest {
    private final Relato relato = Relato.create();

    @Test
    void grupoComQuebraDePaginaEImagem() throws Exception {
        var t = Templates.parse("""
                {"pageHeader":[{"type":"image","src":"classpath:logo.png","width":"20mm"}],
                 "body":[{"type":"table","groups":[{"by":"uf","pageBreak":true,"footer":true}],
                          "columns":[{"header":"Cidade","value":"${cidade}"},{"header":"Pop","value":"${pop}","total":"sum","format":"integer"}]}]}
                """);
        List<Map<String, Object>> rows = List.of(
                Map.of("uf", "PR", "cidade", "Curitiba", "pop", 1_773_000),
                Map.of("uf", "PR", "cidade", "Londrina", "pop", 555_000),
                Map.of("uf", "SC", "cidade", "Joinville", "pop", 616_000),
                Map.of("uf", "RS", "cidade", "Porto Alegre", "pop", 1_332_000));
        byte[] pdf = relato.report(t).data(rows).pdf();
        RelatoTest.save("grupos.pdf", pdf);
        assertEquals(3, RelatoTest.pages(pdf));
        String txt = RelatoTest.text(pdf);
        assertTrue(txt.contains("2.328.000"), txt);
        assertTrue(txt.contains("4.276.000"), txt);
    }
}
