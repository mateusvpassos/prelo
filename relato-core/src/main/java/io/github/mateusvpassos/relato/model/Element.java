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
    @JsonSubTypes.Type(value = Element.Barcode.class, name = "barcode"),
    @JsonSubTypes.Type(value = Element.Include.class, name = "include"),
    @JsonSubTypes.Type(value = Element.Chart.class, name = "chart"),
    @JsonSubTypes.Type(value = Element.Labels.class, name = "labels"),
    @JsonSubTypes.Type(value = Element.Markdown.class, name = "markdown"),
    @JsonSubTypes.Type(value = Element.Crosstab.class, name = "crosstab"),
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
     * @param pageTotals subtotal no pé de cada página ("a transportar") e transporte no topo da seguinte
     */
    record Table(String source, String sortBy, List<TableColumn> columns, List<Group> groups, Boolean showHeader,
                 Boolean zebra, Boolean grid, String totalLabel, String emptyText, Boolean pageTotals, String carryLabel,
                 String broughtLabel, Object style, String visible) implements Element {
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

    /**
     * Código de barras ou QR code.
     *
     * @param format  qr (padrão), code128, code39, ean13, ean8, upca, itf, datamatrix, pdf417
     * @param showText texto legível abaixo (barras lineares)
     */
    record Barcode(String value, String format, String width, String height, Boolean showText, String align,
                   Object style, String visible) implements Element {}

    /**
     * Inclui o corpo de outro template (sub-relatório).
     *
     * @param template nome do outro template (como em {@code relato.report("nome")})
     * @param data     dados do sub-relatório; padrão: o item/dados correntes
     * @param params   parâmetros extras (valores com expressões); os do relatório pai são herdados
     */
    record Include(String template, String data, java.util.Map<String, String> params, Object style, String visible) implements Element {}

    /**
     * Gráfico (imagem gerada no servidor, nítida no PDF).
     *
     * @param chartType bar (padrão), hbar, line, area, pie, donut
     * @param source    coleção; padrão {@code rows}
     * @param label     categoria de cada item (ex.: {@code ${regiao}}); itens com a mesma categoria são somados
     * @param value     valor de cada item (ex.: {@code ${valor}}); ignorado se houver {@code series}
     * @param series    várias séries: cada uma com nome, valor e cor
     * @param colors    paleta (hex); padrão: paleta do relato
     * @param format    formato dos rótulos de valor (ex.: currency)
     */
    record Chart(String chartType, String source, String label, String value, List<Series> series, String title,
                 String width, String height, List<String> colors, Boolean legend, Boolean showValues, String format,
                 String sortBy, Integer limit, String align, Object style, String visible) implements Element {
        public Chart {
            series = series == null ? List.of() : series;
            colors = colors == null ? List.of() : colors;
        }
    }

    record Series(String name, String value, String color) {}

    /**
     * Etiquetas, crachás, cartões: repete {@code content} em grade de tamanho fixo (ex.: folha de etiquetas).
     *
     * @param columns     etiquetas por linha (padrão 2)
     * @param rowsPerPage linhas por folha (quebra de página a cada linhas × colunas); opcional
     * @param width       largura de cada etiqueta (ex.: 63.5mm)
     * @param height      altura de cada etiqueta (ex.: 38.1mm)
     * @param gap         espaço entre etiquetas (ex.: 2.5mm)
     * @param border      contorno (útil para recortar); padrão false
     * @param padding     margem interna (ex.: 3mm)
     * @param copies      cópias de cada item (expressão; ex.: {@code ${quantidade}})
     */
    record Labels(String source, String sortBy, Integer columns, Integer rowsPerPage, String width, String height, String gap,
                  Boolean border, String padding, String copies, List<Element> content, Object style, String visible) implements Element {
        public Labels {
            content = content == null ? List.of() : content;
        }
    }

    /**
     * Texto rico em Markdown (contratos, cartas, certificados): parágrafos, **negrito**, *itálico*, títulos (#),
     * listas (- e 1.), citação (>), linha (---). Expressões ${...} são resolvidas antes e escapadas.
     */
    record Markdown(String value, String align, Object style, String visible) implements Element {}

    /**
     * Tabela cruzada (pivot): linhas × colunas com valor agregado em cada célula.
     *
     * @param rows      expressão da categoria das linhas (ex.: {@code ${regiao}})
     * @param columns   expressão da categoria das colunas (ex.: {@code ${format(data, 'MM/yyyy')}})
     * @param value     expressão somada (ignorada em count)
     * @param aggregate sum (padrão), count, avg, min, max
     * @param totals    linha e coluna de total (padrão true)
     */
    record Crosstab(String source, String rows, String columns, String value, String aggregate, String format,
                    String rowHeader, String totalLabel, Boolean totals, Boolean zebra, Boolean grid, String sortColumns,
                    Object style, String visible) implements Element {}
}
