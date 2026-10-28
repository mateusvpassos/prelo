package io.github.mateusvpassos.prelo.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExprTest {
    private final Formatter fmt = Formatter.brazil();

    private Object eval(String src, Object row) {
        Scope s = Scope.root(fmt, Map.of("param", Map.of("x", 10))).child(row, null, null);
        return Interpolated.of(src).value(s);
    }

    @Test
    void aritmeticaEPrecedencia() {
        assertEquals(new BigDecimal("7"), eval("${1 + 2 * 3}", null));
        assertEquals(new BigDecimal("0.3"), eval("${0.1 + 0.2}", null));
        assertEquals(new BigDecimal("2.5"), eval("${5 / 2}", null));
        assertEquals(true, eval("${param.x > 5 && !(1 == 2)}", null));
        assertEquals("sim", eval("${param.x >= 10 ? 'sim' : 'não'}", null));
        assertEquals("a", eval("${null ?? 'a'}", null));
    }

    @Test
    void propriedadesNuloSeguras() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("cliente", null);
        assertEquals("", eval("Nome: ${cliente.nome}", row).toString().substring(6));
        assertThrows(ExprException.class, () -> eval("${inexistente}", row));
    }

    @Test
    void agregacoes() {
        List<Map<String, Object>> itens = List.of(Map.of("v", 10, "q", 2), Map.of("v", 5, "q", 1));
        Scope s = Scope.root(fmt, Map.of()).child(null, null, itens);
        assertEquals(new BigDecimal("15"), Interpolated.of("${sum(v)}").value(s));
        assertEquals(new BigDecimal("25"), Interpolated.of("${sum(v * q)}").value(s));
        assertEquals(new BigDecimal("2"), Interpolated.of("${count()}").value(s));
        Scope pedido = Scope.root(fmt, Map.of()).child(Map.of("itens", itens), null, null);
        assertEquals(new BigDecimal("2"), Interpolated.of("${count(itens)}").value(pedido));
        assertEquals(new BigDecimal("3"), Interpolated.of("${sum(itens, q)}").value(pedido));
    }

    @Test
    void formatos() {
        assertEquals("R$ 1.234,50", fmt.format(1234.5, "currency").replace(' ', ' '));
        assertEquals("1.234,50", fmt.format(new BigDecimal("1234.5"), "decimal"));
        assertEquals("15%", fmt.format(0.15, "percent"));
        assertEquals("05/09/2026", fmt.format(LocalDate.of(2026, 9, 5), "date"));
        assertEquals("setembro/2026", fmt.format(LocalDate.of(2026, 9, 5), "MMMM/yyyy"));
        assertEquals("12.345.678/0001-95", fmt.format("12345678000195", "cnpj"));
        assertEquals("Não", fmt.toText(false));
        assertEquals("2,5", fmt.toText(2.5));
    }
}
