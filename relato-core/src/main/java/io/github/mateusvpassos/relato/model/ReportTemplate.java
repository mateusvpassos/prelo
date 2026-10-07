package io.github.mateusvpassos.relato.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Definição de um relatório ({@code *.report.json}).
 *
 * @param name        identificador (opcional; o carregador usa o nome do arquivo)
 * @param title       título do documento PDF (metadado)
 * @param locale      ex.: {@code pt-BR} (padrão da engine se nulo)
 * @param page        tamanho, orientação e margens
 * @param style       estilo base do documento (fonte, tamanho...)
 * @param css         CSS adicional, para casos avançados
 * @param parameters  parâmetros esperados (documentação, valores padrão e dados de exemplo)
 * @param dataClass   classe dos dados (ex.: {@code com.empresa.VendaDTO}); o plugin gera {@code fields} a partir dela
 * @param fields      campos dos dados (documentação e dados de exemplo no preview)
 * @param security    senha e permissões do PDF
 * @param metadata    autor, assunto e palavras-chave do PDF
 * @param watermark   marca d'água em todas as páginas
 * @param firstPageHeader cabeçalho só da 1ª página (substitui pageHeader nela)
 * @param pageHeader  repetido no topo de todas as páginas
 * @param body        conteúdo
 * @param pageFooter  repetido no rodapé de todas as páginas
 */
public record ReportTemplate(
        @JsonProperty("$schema") String schema,
        String name,
        String title,
        String locale,
        Page page,
        Object style,
        String css,
        List<ParamDef> parameters,
        String dataClass,
        List<FieldDef> fields,
        Security security,
        Metadata metadata,
        Watermark watermark,
        List<Element> firstPageHeader,
        List<Element> pageHeader,
        List<Element> body,
        List<Element> pageFooter) {

    public ReportTemplate {
        page = page == null ? Page.DEFAULT : page;
        parameters = parameters == null ? List.of() : parameters;
        fields = fields == null ? List.of() : fields;
        firstPageHeader = firstPageHeader == null ? List.of() : firstPageHeader;
        pageHeader = pageHeader == null ? List.of() : pageHeader;
        body = body == null ? List.of() : body;
        pageFooter = pageFooter == null ? List.of() : pageFooter;
    }

    public ReportTemplate withName(String newName) {
        return new ReportTemplate(schema, newName, title, locale, page, style, css, parameters, dataClass, fields, security, metadata, watermark, firstPageHeader, pageHeader, body, pageFooter);
    }
}
