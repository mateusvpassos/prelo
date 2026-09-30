package io.github.mateusvpassos.relato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class RelatoTest {
    public record Cliente(String nome, String cnpj) {}

    public record Venda(String regiao, String vendedor, Cliente cliente, LocalDate data, int quantidade, BigDecimal valor) {}

    public record Item(String produto, int qtd, BigDecimal preco) {}

    public static class Pedido {
        private final int numero;
        private final String cliente;
        private final LocalDate emissao;
        private final boolean pago;
        private final List<Item> itens;
        private final String obs;

        Pedido(int numero, String cliente, LocalDate emissao, boolean pago, List<Item> itens, String obs) {
            this.numero = numero;
            this.cliente = cliente;
            this.emissao = emissao;
            this.pago = pago;
            this.itens = itens;
            this.obs = obs;
        }

        public int getNumero() { return numero; }
        public String getCliente() { return cliente; }
        public LocalDate getEmissao() { return emissao; }
        public boolean isPago() { return pago; }
        public List<Item> getItens() { return itens; }
        public String getObs() { return obs; }
    }

    private final Relato relato = Relato.create();

    static List<Venda> vendas(int n) {
        Random r = new Random(42);
        String[] regioes = {"Sul", "Sudeste", "Norte", "Nordeste"};
        String[] nomes = {"Padaria Pão Quente", "Mercado Bom Preço", "Farmácia Saúde", "Auto Peças Curitiba", "Livraria Saber"};
        List<Venda> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Venda(regioes[i % regioes.length], "V" + (i % 3),
                    new Cliente(nomes[r.nextInt(nomes.length)], String.valueOf(10_000_000_000_000L + r.nextInt(1_000_000))),
                    LocalDate.of(2026, 9, 1 + r.nextInt(29)), 1 + r.nextInt(9), BigDecimal.valueOf(1000 + r.nextInt(900_000), 2)));
        }
        return out;
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            return st.getText(d);
        }
    }

    static int pages(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    static void save(String name, byte[] bytes) throws Exception {
        Path dir = Path.of("build", "out");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), bytes);
        if (name.endsWith(".pdf")) {
            try (PDDocument d = Loader.loadPDF(bytes)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(d);
                for (int i = 0; i < Math.min(2, d.getNumberOfPages()); i++) {
                    javax.imageio.ImageIO.write(r.renderImageWithDPI(i, 80), "png", dir.resolve(name.replace(".pdf", "-p" + (i + 1) + ".png")).toFile());
                }
            }
        }
    }

    @Test
    void vendasComGruposTotaisEPaginacao() throws Exception {
        List<Venda> data = vendas(180);
        byte[] pdf = relato.report("vendas").data(data).param("periodo", "09/2026").pdf();
        save("vendas.pdf", pdf);
        save("vendas.html", relato.report("vendas").data(data).param("periodo", "09/2026").html().getBytes());

        int n = pages(pdf);
        assertTrue(n > 3, "deveria paginar, gerou " + n);
        String txt = text(pdf);
        assertTrue(txt.contains("Página 1 de " + n), txt.substring(0, 400));
        assertTrue(txt.contains("Página " + n + " de " + n));
        // cabeçalho de página e cabeçalho da tabela repetidos em todas as páginas
        assertEquals(n, count(txt, "Relatório de Vendas · período 09/2026"), "cabeçalho de página em toda página");
        assertTrue(count(txt, "Cliente CNPJ") >= n - 1, "cabeçalho da tabela repetido");
        assertTrue(txt.contains("Região: Sul (45 vendas)"));
        BigDecimal total = data.stream().map(Venda::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        String totalFmt = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(total);
        assertTrue(txt.contains("Total geral"));
        assertTrue(txt.replace(' ', ' ').contains(totalFmt.replace(' ', ' ')), "total " + totalFmt);

        byte[] xlsx = relato.report("vendas").data(data).param("periodo", "09/2026").xlsx();
        save("vendas.xlsx", xlsx);
        assertTrue(xlsx.length > 1000);
    }

    @Test
    void mestreDetalheComListaAninhada() throws Exception {
        List<Pedido> pedidos = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            List<Item> itens = new ArrayList<>();
            for (int j = 1; j <= 2 + i % 5; j++) itens.add(new Item("Produto " + j, j, BigDecimal.valueOf(9.9 * j)));
            pedidos.add(new Pedido(1000 + i, "Cliente " + i, LocalDate.of(2026, 9, i), i % 2 == 0, itens, i % 3 == 0 ? "Entregar pela manhã" : null));
        }
        byte[] pdf = relato.report("pedidos").data(pedidos).pdf();
        save("pedidos.pdf", pdf);
        String txt = text(pdf);
        assertTrue(txt.contains("1001"));
        assertTrue(txt.contains("Entregar pela manhã"));
        assertTrue(txt.contains("Total de pedidos: 12"));
        assertTrue(txt.contains("1/" + pages(pdf)));
    }

    @Test
    void erroApontaElementoECampo() {
        ReportException e = assertThrows(ReportException.class, () -> relato.report(Templates.parse("""
                {"body":[{"type":"text","value":"ok"},{"type":"table","columns":[{"header":"X","value":"${naoExiste}"}]}]}
                """)).data(List.of(Map.of("a", 1))).html());
        assertTrue(e.getMessage().contains("body[1]"), e.getMessage());
        assertTrue(e.getMessage().contains("naoExiste"), e.getMessage());

        ReportException e2 = assertThrows(ReportException.class, () -> Templates.parse("""
                {"body":[{"type":"text","valor":"x"}]}
                """));
        assertTrue(e2.getMessage().contains("'valor'"), e2.getMessage());
        assertTrue(e2.getMessage().contains("body[0]"), e2.getMessage());
    }

    @Test
    void relatorioDeObjetoUnicoEParametros() throws Exception {
        String html = relato.report(Templates.parse("""
                {"parameters":[{"name":"titulo","default":"Ficha"}],
                 "body":[{"type":"text","value":"${titulo}: ${nome} (${upper(uf)})"},
                         {"type":"text","value":"${format(salario, 'currency')} · ${cpf}","format":"cpf"}]}
                """)).data(Map.of("nome", "Ana & Cia", "uf", "pr", "salario", 1234.5, "cpf", "12345678909")).html();
        assertTrue(html.contains("Ficha: Ana &amp; Cia (PR)"), html);
        assertTrue(html.contains("123.456.789-09"), html);
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }
}
