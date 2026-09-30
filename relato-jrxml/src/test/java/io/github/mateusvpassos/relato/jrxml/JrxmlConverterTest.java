package io.github.mateusvpassos.relato.jrxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.Templates;
import io.github.mateusvpassos.relato.model.Element;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JrxmlConverterTest {
    @Test
    void converteTabelaComGrupoSubtotalTotalERodape() throws Exception {
        byte[] xml;
        try (InputStream in = getClass().getResourceAsStream("/vendas-teste.jrxml")) {
            xml = in.readAllBytes();
        }
        JrxmlConverter.Result r = JrxmlConverter.convert(xml, "vendas-teste");
        assertEquals(List.of(), r.warnings(), r.json());
        ReportTemplate t = Templates.parse(r.json());
        assertEquals("A4", t.page().size());
        Element.Text title = (Element.Text) t.body().get(0);
        assertEquals("${upper(param.titulo)}", title.value());
        Element.Table table = (Element.Table) t.body().get(1);
        assertEquals("Cliente", table.columns().get(0).header());
        assertEquals("${valor}", table.columns().get(1).value());
        assertEquals("sum", table.columns().get(1).total());
        assertEquals("decimal", table.columns().get(1).format());
        assertEquals("regiao", table.groups().get(0).by());
        assertEquals("Região: ${regiao}", table.groups().get(0).header());
        assertEquals(Boolean.TRUE, table.groups().get(0).footer());
        assertEquals("Subtotal", table.groups().get(0).footerLabel());
        assertEquals("Total geral", table.totalLabel());
        assertEquals("Página ${page}", ((Element.Text) t.pageFooter().get(0)).value());

        String html = Relato.create().report(t).param("titulo", "vendas")
                .data(List.of(Map.of("regiao", "Sul", "cliente", "A", "valor", new BigDecimal("10.5")),
                        Map.of("regiao", "Sul", "cliente", "B", "valor", new BigDecimal("4.5"))))
                .html();
        assertTrue(html.contains("VENDAS"));
        assertTrue(html.contains("15,00"), html);
    }
}
