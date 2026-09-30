package io.github.mateusvpassos.relato.jrxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JavaExprTest {
    private static String t(String java) {
        return JavaExpr.template(JavaExpr.parse(java, n -> n.equals("PAGE_NUMBER") ? "page" : n.equals("TOTAL") ? "sum(valor)" : null));
    }

    @Test
    void traduzOsCasosComuns() {
        assertEquals("${nome}", t("$F{nome}"));
        assertEquals("Página ${page}", t("\"Página \" + $V{PAGE_NUMBER}"));
        assertEquals("${param.titulo}", t("$P{titulo}"));
        assertEquals("${format(data, 'dd/MM/yyyy')}", t("new java.text.SimpleDateFormat(\"dd/MM/yyyy\").format($F{data})"));
        assertEquals("${upper(trim(nome))}", t("$F{nome}.trim().toUpperCase()"));
        assertEquals("${(status == 'A') ? 'Ativo' : 'Inativo'}", t("$F{status}.equals(\"A\") ? \"Ativo\" : \"Inativo\""));
        assertEquals("${cliente.nome}", t("((demo.Cliente)$F{cliente}).getNome()"));
        assertEquals("${(obs != null) ? obs : ''}", t("$F{obs} != null ? $F{obs} : \"\""));
        assertEquals("${format(data, 'dd/MM/yyyy')}", t("new java.text.SimpleDateFormat(\"dd/MM/yyyy\").format($F{data})"));
        assertEquals("${text(numero)}", t("String.valueOf($F{numero})"));
        assertEquals("${ativo}", t("new Boolean($F{ativo}.booleanValue())"));
        assertEquals("Total: ${sum(valor)}", t("\"Total: \" + $V{TOTAL}"));
        assertEquals("${itens}", t("new net.sf.jasperreports.engine.data.JRBeanCollectionDataSource($F{itens})"));
        assertEquals("${substr(cep, 0, 5)}", t("$F{cep}.substring(0,5)"));
        assertEquals("${now()}", t("new java.util.Date()"));
        assertEquals("${(qtd * preco)}", t("($F{qtd} * $F{preco})"));
    }

    @Test
    void recusaOQueNaoSabe() {
        assertThrows(JavaExpr.Unsupported.class, () -> t("$F{x}.compareTo($F{y})"));
        assertThrows(JavaExpr.Unsupported.class, () -> t("$V{DESCONHECIDA}"));
        assertThrows(JavaExpr.Unsupported.class, () -> t("new Foo()"));
    }
}
