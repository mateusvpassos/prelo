package io.github.mateusvpassos.relato.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldCheckTest {
    private static final String BASE = """
            {
              "parameters": [ { "name": "periodo" } ],
              "fields": [
                { "name": "regiao", "type": "string" },
                { "name": "valor", "type": "money" },
                { "name": "cliente", "type": "object", "fields": [ { "name": "nome" }, { "name": "cnpj" } ] },
                { "name": "itens", "type": "list", "fields": [ { "name": "produto" }, { "name": "qtd", "type": "integer" } ] }
              ],
              "body": [ %s ]
            }
            """;

    private static List<FieldCheck.Problem> check(String body) {
        return FieldCheck.check(BASE.formatted(body));
    }

    @Test
    void exemplosValidosNaoTemProblemas() throws Exception {
        for (String f : List.of("vendas", "pedidos")) {
            String json = Files.readString(Path.of("src/test/resources/reports/" + f + ".report.json"));
            assertEquals(List.of(), FieldCheck.check(json), f);
        }
    }

    @Test
    void escoposDeTabelaListaEAgregacoes() {
        String ok = """
                { "type": "text", "value": "${periodo} ${param.periodo} ${cliente.nome} ${sum(valor)} ${count()} ${page}/${pages}" },
                { "type": "table", "sortBy": "regiao, -valor", "groups": [ { "by": "regiao", "header": "${regiao} ${count()}" } ],
                  "columns": [ { "value": "${index} ${cliente.cnpj} ${sum(itens, qtd)} ${count(itens)}", "total": "${sum(valor)}" } ] },
                { "type": "list", "source": "rows", "as": "v", "content": [
                    { "type": "text", "value": "${v.regiao} ${regiao}", "visible": "valor > 0" },
                    { "type": "table", "source": "itens", "columns": [ { "value": "${produto} ${qtd * 2} ${regiao}" } ] }
                  ], "footer": [ { "type": "text", "value": "${sum(valor)}" } ] }
                """;
        assertEquals(List.of(), check(ok));
    }

    @Test
    void acusaCampoInexistenteComPosicao() {
        List<FieldCheck.Problem> ps = check("""
                { "type": "text", "value": "Total: ${vlor}" },
                { "type": "table", "columns": [ { "value": "${cliente.nomee}" } ] },
                { "type": "table", "source": "itens", "columns": [ { "value": "${regiaoo}" } ] },
                { "type": "table", "source": "cliente", "columns": [] },
                { "type": "text", "value": "${itens.produto}" },
                { "type": "text", "value": "${1 +}" }
                """);
        assertEquals(6, ps.size(), ps.toString());
        FieldCheck.Problem p0 = ps.get(0);
        assertEquals("body[0].value", p0.pathString());
        assertEquals(7, p0.start());
        assertEquals(14, p0.end());
        assertTrue(p0.message().contains("'vlor'") && p0.message().contains("valor"), p0.message());
        assertTrue(ps.get(1).message().contains("'nomee' não existe em cliente"), ps.get(1).message());
        assertEquals("body[2].columns[0].value", ps.get(2).pathString());
        assertTrue(ps.get(3).message().contains("não é uma lista"), ps.get(3).message());
        assertTrue(ps.get(4).message().contains("é uma lista"), ps.get(4).message());
        assertTrue(ps.get(5).message().contains("incompleta"), ps.get(5).message());
    }

    @Test
    void semFieldsSoAcusaSintaxe() {
        assertEquals(List.of(), FieldCheck.check("{\"body\":[{\"type\":\"text\",\"value\":\"${qualquer.coisa}\"}]}"));
        assertEquals(1, FieldCheck.check("{\"body\":[{\"type\":\"text\",\"value\":\"${a +}\"}]}").size());
    }

    @Test
    void completaPorEscopo() throws Exception {
        var root = FieldCheck.parse(BASE.formatted("""
                { "type": "table", "source": "itens", "columns": [ { "value": "" } ] }
                """));
        List<Object> colValue = List.of("body", 0, "columns", 0, "value");
        var names = FieldCheck.complete(root, colValue, "").stream().map(FieldCheck.Name::name).toList();
        assertTrue(names.indexOf("produto") < names.indexOf("regiao"), names.toString());
        assertTrue(names.containsAll(List.of("produto", "qtd", "index", "regiao", "periodo", "sum")), names.toString());
        var props = FieldCheck.complete(root, colValue, "x + cliente.n").stream().map(FieldCheck.Name::name).toList();
        assertEquals(List.of("nome", "cnpj"), props);
    }
}
