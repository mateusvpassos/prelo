# relato

Relatórios PDF/XLSX para Java (Spring, Quarkus, puro) a partir de um JSON declarativo, com plugin IntelliJ que mostra o preview enquanto você edita. É uma alternativa leve ao JasperReports.

- **Template `*.report.json`**: cabeçalho e rodapé de página, tabelas com grupos, subtotais e total geral, listas mestre-detalhe, fichas, imagens e colunas.
- **Paginação de verdade**: cabeçalho da tabela repetido em cada página, `Página X de Y` e quebra por grupo.
- **Expressões** `${cliente.nome}`, `${sum(valor)}`, `${qtd * preco}`, `${ativo ? 'Sim' : 'Não'}`.
- **Saídas**: PDF (OpenHTMLtoPDF/PDFBox), XLSX tipado (números e datas reais) e HTML.
- **Licenças livres**: LGPL (openhtmltopdf) e Apache 2 (PDFBox, fastexcel, Jackson).

| Módulo | O que é |
|---|---|
| `relato-core` | engine (Java 17+, sem framework) |
| `relato-fonts` | opcional: DejaVu Sans (Unicode completo) como fonte padrão |
| `relato-jrxml` | migração de `.jrxml` (JasperReports) para `.report.json` (CLI + usado pelo plugin) |
| `relato-spring-boot-starter` | bean `Relato` + `ReportResponses` para controllers |
| `relato-quarkus` | producer CDI (`@Inject Relato`) + `ReportResponses` JAX-RS |
| `relato-intellij` | plugin: designer visual, editor JSON com preview, campos a partir de DTO, migração de `.jrxml` |

## Instalar (local)

```bash
./gradlew publishToMavenLocal
```

Gradle:
```kotlin
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation("io.github.mateusvpassos:relato-spring-boot-starter:0.1.0-SNAPSHOT") // Spring
    // ou relato-quarkus (Quarkus) / relato-core (qualquer Java)
    // opcional: implementation("io.github.mateusvpassos:relato-fonts:0.1.0-SNAPSHOT")    // Unicode completo
}
```

Maven (usa `~/.m2` automaticamente):
```xml
<dependency>
  <groupId>io.github.mateusvpassos</groupId>
  <artifactId>relato-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Plugin IntelliJ: `./gradlew :relato-intellij:buildPlugin` → `Settings → Plugins → ⚙ → Install Plugin from Disk…` → `relato-intellij/build/distributions/relato-intellij-0.1.0.zip`.

Passo a passo com projetos de teste: [TUTORIAL.md](TUTORIAL.md) (`demo-spring/` e `demo-quarkus/`).

> Build: rode o Gradle com o JBR da IDE (`JAVA_HOME="C:/Users/.../IntelliJ IDEA/jbr"`); o plugin usa a IDE local como SDK (`ideaLocalPath` em `gradle.properties`).

## Usar

Templates ficam em `src/main/resources/reports/` (`vendas.report.json`).

**Spring**
```java
@RestController
class RelatoriosController {
    private final Relato relato;
    private final VendaService vendas;

    @GetMapping("/relatorios/vendas")
    ResponseEntity<byte[]> vendas(@RequestParam String periodo) {
        var report = relato.report("vendas").data(vendas.doPeriodo(periodo)).param("periodo", periodo);
        return ReportResponses.inline(report, Format.PDF, "vendas-" + periodo);   // ou download(...), Format.XLSX
    }
}
```
Propriedades (Spring e Quarkus), com os valores padrão:
```properties
relato.templates=classpath:reports/        # ou file:/caminho/reports (edita sem recompilar)
relato.cache=true                          # false em dev
relato.locale=pt-BR
relato.zone=America/Sao_Paulo
relato.date-pattern=dd/MM/yyyy
relato.date-time-pattern=dd/MM/yyyy HH:mm
relato.chunk-rows=4000                     # acima disso o PDF sai em blocos (ver "Relatórios grandes"); 0 desliga
```

**Quarkus** (`io.github.mateusvpassos:relato-quarkus:0.1.0-SNAPSHOT`)
```java
@Path("/relatorios")
public class RelatoriosResource {
    @Inject Relato relato;   // configurado por application.properties (relato.templates, relato.cache...)

    @GET @Path("/vendas")
    public Response vendas(@QueryParam("formato") @DefaultValue("pdf") String formato) {
        return ReportResponses.inline(relato.report("vendas").data(service.vendas()), Format.of(formato), "vendas");
    }
}
```
Projeto de teste: [`demo-quarkus/`](demo-quarkus). Testado em modo JVM; native image não foi validado (PDFBox/AWT).

**Java puro**
```java
Relato relato = Relato.builder().templates(TemplateLoader.classpath("reports/")).build();
byte[] pdf  = relato.report("vendas").data(lista).param("periodo", "09/2026").pdf();
byte[] xlsx = relato.report("vendas").data(lista).xlsx();
relato.report("vendas").data(lista).pdf(outputStream);
```

Dados aceitos: `List`/coleção (vira `rows`, fonte padrão das tabelas), objeto único (propriedades acessíveis direto), records, POJOs com getters, `Map`. JDBC: `Rows.of(resultSet)`.

Comportamentos úteis (iguais aos do Jasper):
- Parâmetro não informado vale `null`, sem erro.
- Com dados em lista, um campo usado fora de tabela/lista (ex.: no cabeçalho de página) lê o 1º registro.
- Imagem cujo caminho vem de expressão e não existe é omitida; um caminho fixo inexistente dá erro.

Erros de template ou de expressão apontam o bloco e o campo: `body[1].columns[0].value: 'vlor' não encontrado (disponíveis na linha: valor, ...)`.

## Template

```json
{
  "title": "Relatório de Vendas",
  "page": { "size": "A4", "orientation": "portrait", "margin": "22mm 12mm 18mm 12mm" },
  "parameters": [ { "name": "periodo", "type": "string", "sample": "09/2026" } ],
  "fields": [ { "name": "regiao", "type": "string" }, { "name": "valor", "type": "money" } ],
  "pageHeader": [ { "type": "text", "value": "Vendas · ${periodo}", "style": { "bold": true, "size": 12 } }, { "type": "line" } ],
  "body": [
    { "type": "table", "sortBy": "regiao, -valor", "zebra": true,
      "groups": [ { "by": "regiao", "header": "Região: ${regiao} (${count()})", "footer": true } ],
      "columns": [
        { "header": "#", "value": "${index}", "width": "8%" },
        { "header": "Cliente", "value": "${cliente.nome}" },
        { "header": "Valor", "value": "${valor}", "format": "currency", "total": "sum", "width": "20%" }
      ] }
  ],
  "pageFooter": [ { "type": "text", "value": "Página ${page} de ${pages}", "align": "right" } ]
}
```

Exemplos completos: [`examples/`](examples) (vendas com grupos, pedidos mestre-detalhe).

### Blocos (`type`)

| type | Para quê | Principais propriedades |
|---|---|---|
| `text` | texto com expressões | `value`, `format`, `align` |
| `table` | lista tabular paginada | `source` (padrão `rows`), `columns[]` (`header value width align format total`), `groups[]` (`by header footer footerLabel pageBreak`), `sortBy`, `zebra`, `grid`, `totalLabel`, `emptyText` |
| `list` | repete blocos por item (mestre-detalhe, fichas) | `source`, `as`, `content[]`, `header[]`, `footer[]`, `separator`, `keepTogether`, `pageBreakBetween` |
| `fields` | grade rótulo: valor | `items[]` (`label value format span`), `columns`, `labelWidth` |
| `row` | colunas lado a lado | `columns[]` (`width align content[]`), `gap` |
| `box` | agrupa com estilo / sem quebrar | `content[]`, `keepTogether` |
| `image` | imagem | `src` (`classpath:`, arquivo, URL, data URI, `${bytes}`), `width`, `height`, `align` |
| `html` | escape hatch (XHTML) | `value` |
| `barcode` | código de barras / QR | `value`, `format` (`qr code128 code39 ean13 ean8 upca itf datamatrix pdf417`), `width`, `height`, `showText` |
| `include` | sub-relatório (corpo de outro template) | `template`, `data`, `params` |
| `line` `spacer` `pageBreak` | | `color thickness` / `height` |

Todos aceitam `style` e `visible` (expressão; ex.: `"visible": "obs != null"`).

`style`: texto CSS ou objeto. Atalhos: `bold italic underline uppercase size align valign font color background border`. Outras chaves viram CSS (`marginTop: 4` → `margin-top:4pt`).

### Recursos de página

```json
{
  "watermark": { "text": "CANCELADO", "visible": "status == 'C'" },
  "firstPageHeader": [ { "type": "text", "value": "Capa: só na 1ª página" } ],
  "body": [
    { "type": "table", "pageTotals": true, "columns": [ { "header": "Valor", "value": "${valor}", "total": "sum" } ] },
    { "type": "barcode", "value": "${chaveNota}", "format": "qr", "width": "30mm" },
    { "type": "barcode", "value": "${codigo}", "format": "code128", "showText": true },
    { "type": "include", "template": "itens-pedido", "data": "itens", "params": { "titulo": "Itens do ${numero}" } }
  ]
}
```
- **`watermark`**: texto ou imagem em todas as páginas, transparente e por baixo do conteúdo.
- **`firstPageHeader`**: cabeçalho próprio da 1ª página; nas outras vale `pageHeader`.
- **`pageTotals`** na tabela: "A transportar" no pé de cada página e "Transporte" no topo da seguinte, com o acumulado das colunas `total: "sum"`. Os rótulos mudam em `carryLabel` e `broughtLabel`.
- **`barcode`**: `qr`, `code128`, `code39`, `ean13`, `ean8`, `upca`, `itf`, `datamatrix`, `pdf417`.
- **`include`**: corpo de outro template (sub-relatório reaproveitável), com os dados e parâmetros que você passar.
- **Unicode completo** (✓ ★ → Ω €, grego, cirílico): adicione `io.github.mateusvpassos:relato-fonts:0.1.0-SNAPSHOT`. Com o jar no classpath, DejaVu Sans vira a fonte padrão.

### Expressões

- **Textos** (`value`, `header`, `label`...) usam `${...}`: `"Total: ${sum(valor)}"`.
- **Propriedades lógicas** (`source`, `visible`, `by`, `sortBy`) aceitam com ou sem `${}`: `"source": "itens"`.
- Navegação nulo-segura (`cliente.endereco.cidade` → vazio se nulo), índice `itens[0]`, `mapa['chave']`.
- Operadores: `+ - * / %`, `== != < <= > >=`, `&& || !` (`and or not`), `a ? b : c`, `a ?? b`.
- Variáveis: `rows`, `data`, `param.x` (ou só `x`), `index` (linha/item), `page`, `pages`, `groupKey`.
- Agregações (no escopo corrente: grupo, tabela, lista ou dados): `sum avg min max count first last join`. Exemplos: `sum(valor)`, `sum(itens, qtd * preco)`, `count()`, `count(itens)`.
- Funções: `format(v, 'padrão') upper lower trim text number len isEmpty coalesce contains replace substr round abs if values keys today now`.

### Formatos (`format`)

`currency`, `number`, `integer`, `decimal`, `percent`, `date`, `datetime`, `time`, `cpf`, `cnpj`, `cpfCnpj`, `cep`, `upper`, `lower`, `'Sim|Não'` (booleano), ou padrão Java (`#,##0.000`, `MMMM/yyyy`).

## Plugin IntelliJ

**Designer visual** (aba *Designer* embaixo do editor):
- Paleta com blocos, prontos (título, `Página X de Y`, data de emissão), **campos dos dados** e parâmetros.
- Arraste para as faixas *cabeçalho da página*, *conteúdo* e *rodapé*, ou para dentro de colunas, caixas e listas.
- Campo solto sobre uma tabela vira coluna (com formato e total pelo tipo); sobre uma ficha, vira item. Um campo `list` solto numa área vazia vira uma tabela com os subcampos.
- Clique para selecionar e edite no painel de propriedades (gerado do schema). Colunas, grupos, itens, estilo (negrito, tamanho, cores...) e condição de visibilidade ficam ali. Duplo clique edita o texto direto.
- Atalhos: Del remove, Ctrl+D duplica, Alt+↑/↓ move, Ctrl+Z/Ctrl+Y desfaz/refaz.
- **Largura das colunas**: arraste a borda entre dois cabeçalhos da tabela, ou entre duas colunas de um bloco *Colunas*.
- **Seleção múltipla**: Ctrl/Shift+clique (Ctrl+A seleciona os irmãos). Dá para remover, duplicar, copiar e aplicar estilo a todos.
- **Copiar/colar**: Ctrl+C/Ctrl+X/Ctrl+V ou o botão ⎘ do bloco e 📋 Colar. Usa o clipboard da IDE, então funciona entre relatórios e até colando JSON de fora.
- **◧ Lado a lado**: layout e PDF juntos, com o PDF atualizando a cada mudança.
- Tudo grava no `.report.json` (desfazer da IDE vale). Editar o texto atualiza o designer. **▶ Preview PDF** mostra o PDF real com os dados de exemplo.
- Comentários `//` do JSON se perdem quando o designer regrava o arquivo.

**Campos a partir de uma classe** (`"dataClass": "com.empresa.VendaDTO"`):
- Designer: ⚙ Relatório → *Classe dos dados* → **Importar campos da classe**. No JSON: **Alt+Enter → Importar campos da classe**.
- Lê record, POJO com getters, Lombok e Kotlin. Listas e objetos viram campos aninhados; `BigDecimal` com nome de valor vira `money`.
- Com os campos declarados, `${...}` tem autocomplete pelo contexto (item da tabela/lista, objetos aninhados) e campo inexistente vira aviso.

**Editor de texto** (aba *Relato*):
- Qualquer `*.report.json` abre com o preview ao lado, que atualiza enquanto você digita, sem salvar.
- Autocomplete, documentação e validação vêm do schema embutido ([`relato.schema.json`](relato-core/src/main/resources/relato.schema.json)).
- Dados do preview: `nome.sample.json` ao lado do template (`{"params": {...}, "data": [...]}`). Se ele não existir, os dados são inventados a partir de `fields`, com valor plausível pelo nome: `cnpj`, `cidade`, `valor`...
- Na barra: zoom, **Dados de exemplo** (cria/abre o sample) e exportar PDF/XLSX/HTML.
- `New → Relatório Relato` cria um template inicial.

## Relatórios grandes

Tabelas com mais de `chunkRows` linhas (padrão 4000) geram o PDF em blocos. Cada bloco termina numa quebra de página medida; os PDFs são juntados em disco e os números de página são carimbados no fim. O documento é o mesmo do modo normal: mesmas quebras, cabeçalhos, totais e "Página X de Y".

| linhas | PDF | XLSX | heap necessário |
|---|---|---|---|
| 5.000 | ~2 s | 0,1 s | < 256 MB |
| 20.000 | ~4 s | 0,2 s | < 256 MB |
| 50.000 (~1.100 páginas) | ~9 s | 0,5 s | < 256 MB (sem blocos: > 1 GB) |

Configuração: `Relato.builder().chunkRows(4000)` ou `relato.chunk-rows=4000`; `0` desliga os blocos. Tabelas com `pageTotals` não usam blocos.

## Migrar do JasperReports

- **No IntelliJ**: clique com o botão direito num `.jrxml` ou numa pasta e escolha **Converter para Relato (.report.json)**. O `.report.json` fica ao lado do original.
- **Em lote, pela linha de comando**:
  ```bash
  ./gradlew :relato-jrxml:run --args="<pasta dos .jrxml> <pasta de saída> <pasta do código Java>"
  ```

Como a conversão funciona:
- As bandas viram blocos em fluxo: os elementos da mesma altura viram colunas.
- O detail de uma linha vira tabela, com os cabeçalhos casados pela posição. O detail de várias linhas vira lista.
- Grupos e variáveis `Sum/Count/Average` viram grupos, subtotais e total geral.
- Sub-relatórios viram `include`. Se o caminho vem de parâmetro (`$P{pathCabecalho}`), o nome é descoberto lendo o código Java (`parametros.put("pathCabecalho", ... "Cabecalho.jasper")`).
- As expressões Java viram expressões do relato: `$F/$P/$V`, concatenação, ternário, `SimpleDateFormat`, getters, `toUpperCase/trim/equals/substring`, `BigDecimal.add`...
- O que não converter fica listado em comentários `//` no topo do arquivo.

Nos 184 `.jrxml` reais de teste (sistemas legados, iReport antigo e JR 6), o resultado foi:
- 183 convertidos (1 `.jrxml` está truncado na origem);
- 0 das 1776 expressões ficaram sem tradução;
- 95% geram o PDF direto com dados de exemplo.

## Desenvolvimento do designer

O designer é HTML/JS puro (`relato-intellij/src/main/resources/designer/`). Para mexer nele fora da IDE, sirva a raiz do repo e abra `dev/designer-demo.html` (host simulado):

```bash
python -m http.server 8765
```
Depois: `http://localhost:8765/dev/designer-demo.html?file=vendas` (ou `?file=pedidos`).

## Roadmap

- [x] Designer visual (arrastar blocos) no plugin
- [x] Designer: redimensionar colunas, copiar/colar, preview lado a lado, seleção múltipla
- [x] `fields` a partir de uma classe DTO do projeto (PSI) + autocomplete de campos nas expressões
- [x] A transportar, marca d'água, cabeçalho só na 1ª página, sub-relatório, código de barras/QR
- [x] Fonte com Unicode completo (`relato-fonts`)
- [x] Relatórios grandes: PDF em blocos com memória limitada
- [x] Migração do JasperReports (`relato-jrxml` + ação no plugin)
- [x] Quarkus (`relato-quarkus`, modo JVM)
- [ ] Quarkus native image
- [ ] Publicar (GitHub Packages) + CI

## Testes

```bash
JAVA_HOME="C:/Users/mvini/AppData/Local/Programs/IntelliJ IDEA/jbr" ./gradlew test
```
Opcionais:
- `-Drelato.perf=true`: medição de volume (5 a 50 mil linhas).
- `-Drelato.jrxml.corpus="pasta1;pasta2"`: migra em lote `.jrxml` reais e mostra o resumo em `relato-jrxml/build/migrados`.

As demos têm build próprio (`demo-spring/`, `demo-quarkus/`) e usam a lib do `mavenLocal`.
