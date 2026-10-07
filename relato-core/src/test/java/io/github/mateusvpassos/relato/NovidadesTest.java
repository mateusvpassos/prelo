package io.github.mateusvpassos.relato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mateusvpassos.relato.expr.BrCodes;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Pix, boleto, gráfico, etiquetas, Markdown, tabela cruzada, segurança e CSV. */
class NovidadesTest {
    private final Relato relato = Relato.create();

    // ---------------------------------------------------------------- Pix e boleto

    @Test
    void crcDoPixConfereComOExemploDoBancoCentral() {
        String semCrc = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***6304";
        assertEquals("1D3D", crc16(semCrc));
    }

    @Test
    void payloadPixValidoComValorENomeSemAcento() {
        String p = BrCodes.pix("12345678909", new BigDecimal("10.5"), "José da Silva", "São Paulo", "PED-123", null);
        assertTrue(p.startsWith("000201"));
        assertTrue(p.contains("0014br.gov.bcb.pix011112345678909"));
        assertTrue(p.contains("540510.50"), p);
        assertTrue(p.contains("5913JOSE DA SILVA"), p);
        assertTrue(p.contains("6009SAO PAULO"), p);
        assertTrue(p.contains("62100506PED123"), p);
        assertEquals(crc16(p.substring(0, p.length() - 4)), p.substring(p.length() - 4));
        // sem valor: campo 54 ausente
        assertFalse(BrCodes.pix("chave@exemplo.com", null, "A", "B", null, null).contains("5405"));
    }

    @Test
    void boletoLinhaDigitavelViraCodigoDeBarras() {
        String barcode = barcodeComDv("0019" + "1000" + "0000012345" + "0000001234567890123456789");
        String linha = linhaDe(barcode);
        assertEquals(47, linha.length());
        assertEquals(barcode, BrCodes.boletoBarcode(linha));
        assertEquals(barcode, BrCodes.boletoBarcode(linha.substring(0, 5) + "." + linha.substring(5, 10) + " " + linha.substring(10)));
        assertThrows(RuntimeException.class, () -> BrCodes.boletoBarcode("123"));
    }

    @Test
    void qrDoPixEBoletoNoRelatorio() throws Exception {
        var t = Templates.parse("""
                {"body":[
                  {"type":"barcode","format":"qr","value":"${pix(chave, valor, 'Loja Exemplo', 'Curitiba', txid)}"},
                  {"type":"text","value":"${pix(chave, valor, 'Loja Exemplo', 'Curitiba', txid)}","style":{"size":6}},
                  {"type":"barcode","format":"itf","value":"${boleto(linha)}","width":"103mm","height":"13mm"}]}
                """);
        String linha = linhaDe(barcodeComDv("0019" + "1000" + "0000012345" + "0000001234567890123456789"));
        byte[] pdf = relato.report(t).data(Map.of("chave", "12345678909", "valor", 99.9, "txid", "A1", "linha", linha)).pdf();
        RelatoTest.save("pix-boleto.pdf", pdf);
        assertTrue(RelatoTest.text(pdf).contains("br.gov.bcb.pix"));
    }

    // ---------------------------------------------------------------- gráfico

    @Test
    void graficosGeramImagem() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] reg = {"Sul", "Sudeste", "Norte", "Nordeste"};
        for (int i = 0; i < 40; i++) rows.add(Map.of("regiao", reg[i % 4], "mes", "0" + (1 + i % 6), "valor", 100 + i * 7, "custo", 60 + i * 3));
        for (String type : List.of("bar", "hbar", "line", "area", "pie", "donut")) {
            var t = Templates.parse("""
                    {"body":[{"type":"chart","chartType":"%s","title":"Vendas","label":"${regiao}",
                      "series":[{"name":"Receita","value":"${valor}"},{"name":"Custo","value":"${custo}"}],
                      "width":"160mm","height":"70mm"}]}
                    """.formatted(type));
            String html = relato.report(t).data(rows).html();
            Matcher m = Pattern.compile("data:image/png;base64,([A-Za-z0-9+/=]+)").matcher(html);
            assertTrue(m.find(), type);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(m.group(1))));
            assertTrue(img.getWidth() > 1000, type + " nítido: " + img.getWidth());
            // linha é traço fino: pouca área colorida
            double min = type.equals("line") ? 0.003 : 0.02;
            assertTrue(colored(img) > min, type + " desenhou algo: " + colored(img));
        }
        var t = Templates.parse("""
                {"body":[{"type":"text","value":"Painel"},
                 {"type":"row","columns":[
                   {"width":"50%","content":[{"type":"chart","chartType":"bar","title":"Por região","label":"${regiao}","value":"${valor}","width":"85mm","height":"60mm","format":"integer"}]},
                   {"width":"50%","content":[{"type":"chart","chartType":"donut","title":"Participação","label":"${regiao}","value":"${valor}","width":"85mm","height":"60mm"}]}]},
                 {"type":"chart","chartType":"line","title":"Por mês","label":"${mes}","sortBy":"mes","series":[{"name":"Receita","value":"${valor}"},{"name":"Custo","value":"${custo}","color":"#d64545"}],"width":"180mm","height":"70mm"}]}
                """);
        RelatoTest.save("graficos.pdf", relato.report(t).data(rows).pdf());
    }

    private static double colored(BufferedImage img) {
        int n = 0, total = 0;
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40) n++;
                total++;
            }
        }
        return (double) n / total;
    }

    // ---------------------------------------------------------------- etiquetas

    @Test
    void etiquetasEmGradeComCopiasEFolhas() throws Exception {
        var t = Templates.parse("""
                {"page":{"size":"A4","margin":"10mm"},
                 "body":[{"type":"labels","columns":3,"rowsPerPage":7,"width":"60mm","height":"36mm","gap":"2mm","border":true,"copies":"${qtd}",
                   "content":[
                     {"type":"text","value":"${nome}","style":{"bold":true}},
                     {"type":"row","columns":[
                       {"width":"60%","content":[{"type":"text","value":"Cód. ${codigo}"},{"type":"barcode","format":"code128","value":"${codigo}","width":"30mm","height":"9mm"}]},
                       {"width":"40%","content":[{"type":"barcode","format":"qr","value":"https://exemplo.com/p/${codigo}","width":"18mm"}]}]}]}]}
                """);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 15; i++) rows.add(Map.of("nome", "Produto " + i, "codigo", "P" + (1000 + i), "qtd", 2));
        byte[] pdf = relato.report(t).data(rows).pdf();
        RelatoTest.save("etiquetas.pdf", pdf);
        assertEquals(2, RelatoTest.pages(pdf), "30 etiquetas, 21 por folha");
        String txt = RelatoTest.text(pdf);
        assertEquals(30, count(txt, "Cód. P10"));
    }

    // ---------------------------------------------------------------- markdown

    @Test
    void markdownComExpressoesEscapadas() {
        var t = Templates.parse("""
                {"body":[{"type":"markdown","value":"# Contrato nº ${numero}\\n\\nO **CONTRATANTE** ${nome}, inscrito no CPF ${format(cpf, 'cpf')}, declara:\\n\\n1. aceitar *todas* as cláusulas;\\n2. pagar ${format(valor, 'currency')}.\\n\\n> Observação: ${obs}\\n\\n---\\nCuritiba, ${data}."}]}
                """);
        String html = relato.report(t).data(Map.of("numero", 42, "nome", "Ana <script> & *Cia*", "cpf", "12345678909",
                "valor", 1500, "obs", "sem reajuste", "data", java.time.LocalDate.of(2026, 10, 7))).html();
        assertTrue(html.contains("<h1 class=\"rl-md-h\">Contrato nº 42</h1>"), html);
        assertTrue(html.contains("<b>CONTRATANTE</b> Ana &lt;script&gt; &amp; *Cia*"), "valor não vira marcação: " + html);
        assertTrue(html.contains("<li>aceitar <i>todas</i> as cláusulas;</li>"));
        assertTrue(html.contains("123.456.789-09"));
        assertTrue(html.contains("<blockquote>Observação: sem reajuste</blockquote>"), html);
        assertTrue(html.contains("<hr/>"));
        assertTrue(html.contains("07/10/2026"));
    }

    // ---------------------------------------------------------------- tabela cruzada

    @Test
    void tabelaCruzadaComTotais() throws Exception {
        var t = Templates.parse("""
                {"body":[{"type":"crosstab","rows":"${regiao}","columns":"${mes}","value":"${valor}","format":"integer","rowHeader":"Região","sortColumns":"asc"}]}
                """);
        List<Map<String, Object>> rows = List.of(
                Map.of("regiao", "Sul", "mes", "02", "valor", 10), Map.of("regiao", "Sul", "mes", "01", "valor", 5),
                Map.of("regiao", "Norte", "mes", "01", "valor", 7), Map.of("regiao", "Sul", "mes", "01", "valor", 1));
        byte[] pdf = relato.report(t).data(rows).pdf();
        String txt = RelatoTest.text(pdf);
        assertTrue(txt.contains("Região 01 02 Total"), txt);
        assertTrue(txt.contains("Sul 6 10 16"), txt);
        assertTrue(txt.contains("Norte 7 7"), txt);
        assertTrue(txt.contains("Total 13 10 23"), txt);
        String csv = new String(relato.report(t).data(rows).csv(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Região;01;02;Total"), csv);
        assertTrue(csv.contains("Sul;6;10;16"), csv);
    }

    // ---------------------------------------------------------------- segurança, metadados, CSV

    @Test
    void pdfComSenhaPermissoesEMetadados() throws Exception {
        var t = Templates.parse("""
                {"title":"Extrato","metadata":{"author":"Banco Exemplo","subject":"Extrato de ${mes}","keywords":"extrato"},
                 "security":{"userPassword":"${cpf}","allowCopy":false},
                 "body":[{"type":"text","value":"Saldo: ${saldo}"}]}
                """);
        byte[] pdf = relato.report(t).data(Map.of("cpf", "12345678909", "mes", "09/2026", "saldo", 10)).pdf();
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(pdf).close());
        try (PDDocument d = Loader.loadPDF(pdf, "12345678909")) {
            assertTrue(d.isEncrypted());
            assertFalse(d.getCurrentAccessPermission().canExtractContent());
            assertTrue(d.getCurrentAccessPermission().canPrint());
            assertEquals("Banco Exemplo", d.getDocumentInformation().getAuthor());
            assertEquals("Extrato de 09/2026", d.getDocumentInformation().getSubject());
            assertEquals("Extrato", d.getDocumentInformation().getTitle());
        }
    }

    @Test
    void csvDaPrimeiraTabela() {
        var t = Templates.parse("""
                {"body":[{"type":"text","value":"x"},{"type":"table","columns":[
                  {"header":"Nome","value":"${nome}"},{"header":"Valor","value":"${valor}","format":"currency"}]}]}
                """);
        byte[] csv = relato.report(t).data(List.of(Map.of("nome", "A; B", "valor", new BigDecimal("1234.50")), Map.of("nome", "C \"x\"", "valor", 2))).csv();
        assertEquals((byte) 0xEF, csv[0]);
        String s = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertEquals("Nome;Valor\r\n\"A; B\";1234,5\r\n\"C \"\"x\"\"\";2\r\n", s);
    }

    @Test
    void estiloCondicionalPorLinha() {
        var t = Templates.parse("""
                {"body":[{"type":"text","value":"x","style":{"color":"${ok ? 'green' : 'red'}"}},
                  {"type":"table","source":"itens","columns":[{"header":"Saldo","value":"${saldo}","style":{"color":"${saldo < 0 ? '#c0392b' : ''}","bold":true}}]}]}
                """);
        String html = relato.report(t).data(Map.of("ok", false, "itens", List.of(Map.of("saldo", -5), Map.of("saldo", 7)))).html();
        assertTrue(html.contains("color:red"), html);
        assertTrue(html.contains("color:#c0392b;font-weight:bold"), html);
        assertTrue(html.contains("<td style=\"text-align:right;font-weight:bold;\">7</td>") || html.matches("(?s).*<td[^>]*style=\"[^\"]*font-weight:bold;?\"[^>]*>7</td>.*"), html);
        assertFalse(html.contains("${"), html);
    }

    // ---------------------------------------------------------------- utilitários

    private static int count(String s, String sub) {
        int c = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) c++;
        return c;
    }

    private static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    /** Monta um código de barras de boleto (43 dígitos sem o DV) com o DV geral (módulo 11). */
    private static String barcodeComDv(String sem) {
        String s43 = sem.substring(0, 4) + sem.substring(4);
        int sum = 0, w = 2;
        for (int i = s43.length() - 1; i >= 0; i--) {
            sum += (s43.charAt(i) - '0') * w;
            w = w == 9 ? 2 : w + 1;
        }
        int dv = 11 - sum % 11;
        if (dv == 0 || dv == 10 || dv == 11) dv = 1;
        return s43.substring(0, 4) + dv + s43.substring(4);
    }

    /** Linha digitável a partir do código de barras (com os DVs módulo 10 dos campos). */
    private static String linhaDe(String b) {
        String f1 = b.substring(0, 4) + b.substring(19, 24);
        String f2 = b.substring(24, 34);
        String f3 = b.substring(34, 44);
        return f1 + mod10(f1) + f2 + mod10(f2) + f3 + mod10(f3) + b.charAt(4) + b.substring(5, 19);
    }

    private static int mod10(String s) {
        int sum = 0, w = 2;
        for (int i = s.length() - 1; i >= 0; i--) {
            int p = (s.charAt(i) - '0') * w;
            sum += p > 9 ? p - 9 : p;
            w = w == 2 ? 1 : 2;
        }
        return (10 - sum % 10) % 10;
    }
}
