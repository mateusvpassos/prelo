package io.github.mateusvpassos.relato.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Bloco do relatório. Todos aceitam {@code style} (objeto ou texto CSS) e {@code visible}
 * (expressão; o bloco some quando falsa).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Element.Text.class, name = "text"),
    @JsonSubTypes.Type(value = Element.Html.class, name = "html"),
    @JsonSubTypes.Type(value = Element.Image.class, name = "image"),
    @JsonSubTypes.Type(value = Element.Row.class, name = "row"),
    @JsonSubTypes.Type(value = Element.Box.class, name = "box"),
    @JsonSubTypes.Type(value = Element.Table.class, name = "table"),
    @JsonSubTypes.Type(value = Element.ListBlock.class, name = "list"),
    @JsonSubTypes.Type(value = Element.Fields.class, name = "fields"),
    @JsonSubTypes.Type(value = Element.Line.class, name = "line"),
    @JsonSubTypes.Type(value = Element.Spacer.class, name = "spacer"),
    @JsonSubTypes.Type(value = Element.PageBreak.class, name = "pageBreak"),
})
public sealed interface Element {
    Object style();

    String visible();

    /** Texto com expressões. {@code format} aplica-se quando {@code value} é uma única expressão. */
    record Text(String value, String format, String align, Object style, String visible) implements Element {}

    /** HTML livre (XHTML); os valores das expressões são escapados. */
    record Html(String value, Object style, String visible) implements Element {}

    /**
     * Imagem: {@code classpath:img/logo.png}, caminho de arquivo, URL http(s), data URI
     * ou expressão que devolve {@code byte[]}.
     */
    record Image(String src, String width, String height, String align, Object style, String visible) implements Element {}

    /** Colunas lado a lado. */
    record Row(List<Column> columns, String gap, Object style, String visible) implements Element {
        public Row {
            columns = columns == null ? List.of() : columns;
        }
    }

    record Column(String width, String align, Object style, List<Element> content) {
        public Column {
            content = content == null ? List.of() : content;
        }
    }

    /** Agrupa blocos com um estilo (borda, fundo...). {@code keepTogether} evita quebrar entre páginas. */
    record Box(List<Element> content, Boolean keepTogether, Object style, String visible) implements Element {
        public Box {
            content = content == null ? List.of() : content;
        }
    }

    /**
     * Tabela de dados: cabeçalho repetido em cada página, grupos com subtotais e total geral.
     *
     * @param source     coleção; padrão {@code ${rows}} (os dados do relatório)
     * @param sortBy     expressão de ordenação; prefixo "-" para decrescente
     * @param groups     agrupamentos aninhados (na ordem)
     * @param totalLabel rótulo da linha de total geral (padrão "Total"); só aparece se alguma coluna tem {@code total}
     * @param zebra      linhas alternadas
     * @param grid       bordas em todas as células
     * @param emptyText  mostrado quando não há linhas
     */
    record Table(String source, String sortBy, List<TableColumn> columns, List<Group> groups, Boolean showHeader,
                 Boolean zebra, Boolean grid, String totalLabel, String emptyText, Object style, String visible) implements Element {
        public Table {
            columns = columns == null ? List.of() : columns;
            groups = groups == null ? List.of() : groups;
        }
    }

    /**
     * @param value  expressão do valor, ex.: {@code ${cliente.nome}}
     * @param total  {@code sum avg min max count}, uma expressão ({@code ${sum(valor) / count()}}) ou texto fixo
     */
    record TableColumn(String header, String value, String width, String align, String format, String total,
                       Object style, Object headerStyle) {}

    /**
     * @param by        expressão da chave do grupo
     * @param header    texto do cabeçalho do grupo (avaliado no 1º item; {@code count()} e {@code sum()} valem para o grupo)
     * @param footer    mostra subtotais das colunas com {@code total}
     * @param pageBreak cada grupo começa em nova página (só no 1º nível)
     */
    record Group(String by, String header, Boolean footer, String footerLabel, Boolean pageBreak, Object style) {}

    /**
     * Repete {@code content} para cada item, sem forma de tabela (fichas, blocos mestre-detalhe).
     * Dentro de cada item, as propriedades do item ficam acessíveis direto e pelo nome em {@code as} (padrão {@code item});
     * {@code index} é a posição (1, 2, ...).
     */
    record ListBlock(String source, String as, String sortBy, List<Element> header, List<Element> content, List<Element> footer,
                     Boolean separator, Boolean pageBreakBetween, Boolean keepTogether, String emptyText,
                     Object style, String visible) implements Element {
        public ListBlock {
            header = header == null ? List.of() : header;
            content = content == null ? List.of() : content;
            footer = footer == null ? List.of() : footer;
        }
    }

    /** Pares rótulo: valor em grade (ficha cadastral). {@code columns} = pares por linha (padrão 2). */
    record Fields(List<FieldItem> items, Integer columns, String labelWidth, Object style, String visible) implements Element {
        public Fields {
            items = items == null ? List.of() : items;
        }
    }

    record FieldItem(String label, String value, String format, Integer span) {}

    record Line(String color, String thickness, Object style, String visible) implements Element {}

    record Spacer(String height, Object style, String visible) implements Element {}

    record PageBreak(Object style, String visible) implements Element {}
}
