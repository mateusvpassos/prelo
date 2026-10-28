package io.github.mateusvpassos.prelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** PDF em blocos: mesmo conteúdo, mesmas quebras e numeração do modo normal. */
class ChunkedTest {
    private static final String TPL = """
            {"watermark":{"text":"RASCUNHO"},
             "pageHeader":[{"type":"text","value":"Cabeçalho"}],
             "pageFooter":[{"type":"text","value":"Página ${page} de ${pages}","align":"right"}],
             "body":[{"type":"text","value":"Antes da tabela"},
               {"type":"table","groups":[{"by":"g","footer":true}],"columns":[
                 {"header":"N","value":"${n}"},{"header":"Valor","value":"${v}","total":"sum"}]},
               {"type":"text","value":"Depois da tabela"}]}
            """;

    private static List<String> pages(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                st.setStartPage(p);
                st.setEndPage(p);
                out.add(st.getText(d));
            }
        }
        return out;
    }

    @Test
    void blocosGeramOMesmoDocumento() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 3000; i++) rows.add(Map.of("g", "G" + (i / 700), "n", i, "v", i));
        var t = Templates.parse(TPL);
        byte[] normal = Prelo.builder().chunkRows(0).build().report(t).data(rows).pdf();
        byte[] chunked = Prelo.builder().chunkRows(400).build().report(t).data(rows).pdf();
        PreloTest.save("chunked.pdf", chunked);
        List<String> a = pages(normal), b = pages(chunked);
        assertEquals(a.size(), b.size(), "mesmo número de páginas");
        int n = b.size();
        Pattern rowRe = Pattern.compile("(?m)^(\\d+) \\1$");
        int expected = 1;
        for (int p = 0; p < n; p++) {
            String txt = b.get(p).replaceAll(" +", " ");
            assertTrue(txt.contains("Página " + (p + 1) + " de " + n), "numeração na página " + (p + 1) + ":\n" + txt);
            assertTrue(txt.contains("Cabeçalho"));
            assertTrue(txt.contains("RASCUNHO"));
            Matcher m = rowRe.matcher(txt);
            while (m.find()) {
                assertEquals(expected, Integer.parseInt(m.group(1)), "linhas em ordem, sem repetir nem pular");
                expected++;
            }
            assertEquals(a.get(p).replaceAll("\\s+", "").replace("RASCUNHO", ""), b.get(p).replaceAll("\\s+", "").replace("RASCUNHO", ""),
                    "página " + (p + 1) + " igual ao modo normal");
        }
        assertEquals(3001, expected);
        assertTrue(b.get(0).contains("Antes da tabela"));
        assertTrue(b.get(n - 1).contains("Depois da tabela"));
    }
}
