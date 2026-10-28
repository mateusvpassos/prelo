package io.github.mateusvpassos.prelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Cabeçalho da 1ª página, marca d'água, código de barras, sub-relatório e totais por página. */
class RecursosTest {
    private final Prelo prelo = Prelo.create();

    private static List<String> pageTexts(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                st.setStartPage(p);
                st.setEndPage(p);
                out.add(st.getText(d).replace(' ', ' '));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> linhas(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) rows.add(Map.of("n", i, "desc", "Item " + i, "valor", new BigDecimal(i + ".50")));
        return rows;
    }

    @Test
    void cabecalhoDaPrimeiraPaginaEMarcaDagua() throws Exception {
        var t = Templates.parse("""
                {"watermark": {"text": "${status}", "visible": "status != 'OK'"},
                 "firstPageHeader": [{"type":"text","value":"CAPA DO RELATÓRIO"}],
                 "pageHeader": [{"type":"text","value":"cabeçalho normal"}],
                 "body": [{"type":"table","source":"itens","columns":[{"header":"Item","value":"${desc}"}]}]}
                """);
        byte[] pdf = prelo.report(t).data(Map.of("status", "CANCELADO", "itens", linhas(120))).pdf();
        PreloTest.save("primeira-pagina.pdf", pdf);
        List<String> pages = pageTexts(pdf);
        assertTrue(pages.size() >= 3);
        assertTrue(pages.get(0).contains("CAPA DO RELATÓRIO"), pages.get(0));
        assertFalse(pages.get(0).contains("cabeçalho normal"), pages.get(0));
        for (int i = 1; i < pages.size(); i++) {
            assertTrue(pages.get(i).contains("cabeçalho normal"), "página " + (i + 1));
            assertFalse(pages.get(i).contains("CAPA DO RELATÓRIO"));
        }
        for (String p : pages) assertTrue(p.contains("CANCELADO"), "marca d'água em toda página");

        byte[] ok = prelo.report(t).data(Map.of("status", "OK", "itens", linhas(3))).pdf();
        assertFalse(pageTexts(ok).get(0).contains("CANCELADO"));
    }

    @Test
    void sampleConverteDataEHoraIsoSemFields() {
        var t = Templates.parse("""
                {"body":[{"type":"table","columns":[{"header":"Quando","value":"${dt}","format":"datetime"}]}]}
                """);
        var s = SampleData.parse("""
                {"data":[{"d":"2026-01-05","dt":"2026-01-05T14:30:00","txt":"2026-01-05 x"}]}
                """, t);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) s.data()).get(0);
        assertEquals(java.time.LocalDate.of(2026, 1, 5), row.get("d"));
        assertEquals(java.time.LocalDateTime.of(2026, 1, 5, 14, 30), row.get("dt"));
        assertEquals("2026-01-05 x", row.get("txt"));
        String csv = new String(prelo.report(t).data(s.data()).csv(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("05/01/2026 14:30"), csv);
    }

    @Test
    void boletoRespeitaLarguraMesmoComPngMaisLargoQueAColuna() {
        // PNG do ITF de 44 dígitos é mais largo que a página; sem max-width:none o PDF esticava até a margem
        var t = Templates.parse("""
                {"body":[{"type":"barcode","format":"itf","value":"${boleto('23793381286000782713695000063305975520000370000')}","width":"103mm","height":"13mm"}]}
                """);
        String html = prelo.report(t).data(Map.of()).html();
        assertTrue(html.contains("width:103mm;height:13mm;max-width:none;"), html);
    }

    @Test
    void etiquetasSemLarguraDividemAPagina() {
        var t = Templates.parse("""
                {"body":[{"type":"labels","columns":2,"gap":"5mm","content":[{"type":"text","value":"${n}"}]}]}
                """);
        String html = prelo.report(t).data(linhas(3)).html();
        assertTrue(html.contains("width:100%;table-layout:fixed"), html);
        assertTrue(html.contains("width:50.0000%"), html);
        String fixa = prelo.report(Templates.parse("""
                {"body":[{"type":"labels","width":"70mm","content":[{"type":"text","value":"${n}"}]}]}
                """)).data(linhas(1)).html();
        assertTrue(fixa.contains("width:70mm") && !fixa.contains("table-layout:fixed"), fixa);
    }

    @Test
    void codigoDeBarrasEQr() throws Exception {
        var t = Templates.parse("""
                {"body":[
                  {"type":"barcode","value":"${chave}","format":"qr","width":"30mm"},
                  {"type":"barcode","value":"${codigo}","format":"code128","showText":true},
                  {"type":"barcode","value":"7891234567895","format":"ean13"}]}
                """);
        String html = prelo.report(t).data(Map.of("chave", "https://exemplo.com/nota/123", "codigo", "PED-2026-0001")).html();
        assertEquals(3, html.split("data:image/png;base64,", -1).length - 1);
        assertTrue(html.contains("PED-2026-0001"));
        byte[] pdf = prelo.report(t).data(Map.of("chave", "abc", "codigo", "X1")).pdf();
        PreloTest.save("barcode.pdf", pdf);

        ReportException e = org.junit.jupiter.api.Assertions.assertThrows(ReportException.class, () ->
                prelo.report(Templates.parse("{\"body\":[{\"type\":\"barcode\",\"value\":\"abc\",\"format\":\"ean13\"}]}")).html());
        assertTrue(e.getMessage().contains("ean13"), e.getMessage());
    }

    @Test
    void subRelatorioComDadosEParametros() throws Exception {
        // reports/itens-sub.report.json no classpath de teste
        var t = Templates.parse("""
                {"parameters":[{"name":"empresa","default":"ACME"}],
                 "body":[{"type":"list","source":"rows","as":"p","content":[
                    {"type":"text","value":"Pedido ${numero}"},
                    {"type":"include","template":"itens-sub","data":"itens","params":{"titulo":"Itens do ${numero}"}}]}]}
                """);
        List<Map<String, Object>> pedidos = List.of(
                Map.of("numero", 1, "itens", linhas(2)),
                Map.of("numero", 2, "itens", linhas(3)));
        String html = prelo.report(t).data(pedidos).html();
        assertTrue(html.contains("Itens do 1 · ACME"), html);
        assertTrue(html.contains("Itens do 2 · ACME"));
        assertTrue(html.contains("Item 3"));
        assertTrue(html.contains("Soma: 7,5"), html); // 1.5 + 2.5 + 3.5
    }

    @Test
    void totaisPorPaginaATransportar() throws Exception {
        var t = Templates.parse("""
                {"pageFooter":[{"type":"text","value":"${page}/${pages}"}],
                 "body":[{"type":"text","value":"Lançamentos"},
                   {"type":"table","pageTotals":true,"columns":[
                     {"header":"Nº","value":"${n}","width":"15%"},
                     {"header":"Descrição","value":"${desc}"},
                     {"header":"Valor","value":"${valor}","format":"decimal","total":"sum","width":"25%"}]}]}
                """);
        List<Map<String, Object>> rows = linhas(150);
        byte[] pdf = prelo.report(t).data(rows).pdf();
        PreloTest.save("a-transportar.pdf", pdf);
        List<String> pages = pageTexts(pdf);
        assertTrue(pages.size() >= 3, "páginas: " + pages.size());

        var nf = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR"));
        nf.setMinimumFractionDigits(2);
        Pattern rowNum = Pattern.compile("(?m)^(\\d+) Item \\1 ");
        for (int p = 0; p < pages.size(); p++) {
            String txt = pages.get(p);
            Matcher m = rowNum.matcher(txt);
            int last = -1;
            while (m.find()) last = Integer.parseInt(m.group(1));
            boolean lastPage = p == pages.size() - 1;
            if (!lastPage) {
                // soma acumulada até a última linha desta página
                BigDecimal acc = BigDecimal.ZERO;
                for (int i = 1; i <= last; i++) acc = acc.add(new BigDecimal(i + ".50"));
                String carry = "A transportar " + nf.format(acc);
                assertTrue(txt.contains(carry), "página " + (p + 1) + " sem '" + carry + "':\n" + txt);
                assertTrue(pages.get(p + 1).contains("Transporte " + nf.format(acc)), "página " + (p + 2) + " sem transporte");
            } else {
                assertEquals(150, last);
                assertFalse(txt.contains("A transportar"));
                assertTrue(txt.contains("Total"));
            }
        }
    }
}
