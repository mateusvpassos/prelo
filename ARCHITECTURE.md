# Arquitetura do prelo

Guia para quem vai mexer no código. Para *usar* a biblioteca, veja o [README](README.md) e o [TUTORIAL](TUTORIAL.md).

## Visão geral

```
                     ┌──────────────────────── prelo-core ─────────────────────────┐
 *.report.json ──►   │ Templates.parse ─► ReportTemplate (model)                    │
                     │        │                                                      │
 dados + params ──►  │   Evaluator ──(expr: Parser/Scope/Functions/Formatter)──►     │
                     │        │                                                      │
                     │        ▼                                                      │
                     │   Node.Document  (árvore já avaliada: textos prontos,         │
                     │        │           linhas de tabela, totais, imagens)         │
                     │        ├──► XlsxRenderer ──────────────────────────► .xlsx    │
                     │        ├──► HtmlRenderer ──────────────────────────► .html    │
                     │        └──► PageTotals / ChunkedPdf                           │
                     │                 └► HtmlRenderer ─► PdfRenderer (openhtmltopdf)│
                     │                                   └► Watermarks (PDFBox) ─► .pdf
                     └───────────────────────────────────────────────────────────────┘
      ▲                      ▲                          ▲
      │                      │                          │
 prelo-intellij       prelo-spring-boot-starter   prelo-jrxml (.jrxml ─► .report.json)
 (designer, preview,   prelo-quarkus               prelo-fonts (FontSource via ServiceLoader)
  FieldCheck no JSON)
```

A regra que orienta tudo: **o template é dado, não código**. O JSON é a fonte da verdade. A engine, o designer, o preview, o conversor do Jasper e o autocomplete são leitores e escritores desse mesmo JSON, e o `prelo.schema.json` é o contrato comum entre eles.

## Módulos

| Módulo | Depende de | Papel |
|---|---|---|
| `prelo-core` | Jackson, openhtmltopdf, PDFBox, fastexcel, ZXing | engine; Java 17, sem framework |
| `prelo-fonts` | core (compileOnly) | DejaVu Sans registrada via `ServiceLoader` |
| `prelo-jrxml` | core | conversor Jasper, CLI, ligação de sub-relatórios |
| `prelo-spring-boot-starter` | core, Spring (compileOnly) | auto-config + `ReportResponses` |
| `prelo-quarkus` | core, CDI/MP Config/JAX-RS (compileOnly) | producer CDI (`META-INF/beans.xml`) + `ReportResponses` |
| `prelo-server` | core, fonts | CLI (`render`, `check`) e serviço HTTP (JDK `HttpServer`); `fatJar` gera o jar único |
| `prelo-intellij` | core, jrxml, PDFBox, plataforma IntelliJ | plugin (Kotlin + designer em HTML/JS) |
| `demo-spring`, `demo-quarkus` | artefatos do `mavenLocal` | builds separados, fora do `settings.gradle.kts` |

As integrações só declaram as APIs de framework como `compileOnly`. Quem traz Spring ou Quarkus é o projeto do usuário.

---

## prelo-core

Pacote base: `io.github.mateusvpassos.prelo`.

### API pública (pacote raiz)

- **`Prelo`**: fachada thread-safe. O builder reúne loader, locale/zona/padrões de data, resolver de recursos, fontes e `chunkRows`. Guarda os templates em cache (`ConcurrentHashMap`) se `cache=true`.
- **`ReportRequest`**: um pedido (dados + parâmetros), com as saídas `pdf()/xlsx()/html()/render(Format)`. Não é thread-safe.
- **`Templates`**: parse do JSON com Jackson. Tem `FAIL_ON_UNKNOWN_PROPERTIES` ligado de propósito, para pegar erro de digitação, e aceita comentários e vírgula sobrando. Traduz as exceções do Jackson para mensagens com o caminho (`body[1].columns[0]`).
- **`TemplateLoader`**: carrega por nome, do classpath ou de uma pasta, acrescentando o sufixo `.report.json`.
- **`ResourceResolver`**: resolve `src` de imagens (`classpath:`, arquivo relativo a uma pasta base).
- **`SampleData`**: dados de exemplo. Lê um `*.sample.json` ou inventa valores a partir de `fields` (valor plausível pelo nome e tipo). Usado pelo plugin e pelos testes.
- **`FontSource`**: ponto de extensão (SPI) para fontes; `prelo-fonts` implementa.
- **`Rows`**: adaptador JDBC (`ResultSet` → lista de mapas).

### `model`: o template tipado

Records Jackson que espelham o JSON: `ReportTemplate`, `Page`, `ParamDef`, `FieldDef`, `Watermark` e `Element`.

`Element` é uma `sealed interface` com `@JsonTypeInfo(property = "type")`, e cada tipo de bloco é um record aninhado (`Text`, `Table`, `ListBlock`, `Barcode`, `Include`...). Os construtores compactos trocam listas nulas por vazias, para o resto do código nunca testar `null`.

Os campos com texto ficam como `String` crua (`"${cliente.nome}"`). A interpretação acontece na avaliação, não no parse.

### `expr`: a linguagem de expressões

| Classe | Faz |
|---|---|
| `Interpolated` | separa `"Total: ${sum(valor)}"` em literais e `Expr` (com cache por texto). `value()` devolve o valor cru se o texto é uma única expressão; `text()` monta a string |
| `Parser` | descida recursiva: ternário → `\|\|`/`??` → `&&` → igualdade → relacional → soma → produto → unário → posfixo (`.x`, `[i]`) → primário. Nome de função desconhecido é erro de parse |
| `Expr` | AST (`sealed`): `Literal Ident Prop Index Call Unary Binary Conditional`; cada nó tem `eval(Scope)` |
| `Scope` | cadeia de escopos. Cada nível tem uma **linha** (objeto cujas propriedades valem como nomes), **variáveis** e **itens** (a coleção das agregações) |
| `Values` | acesso a propriedades (Map, record, getter, campo; accessors em cache por `ClassValue`), aritmética em `BigDecimal`, igualdade e comparação nulo-seguras, conversão para lista |
| `Functions` | funções comuns e agregações. As agregações recebem a `Expr` sem avaliar e avaliam por item |
| `Formatter` | texto padrão por tipo e os formatos nomeados (`currency`, `cnpj`...) ou por padrão Java |
| `PageRef` | `${page}`/`${pages}` não têm valor na avaliação; viram marcadores que o renderizador trata |

**Resolução de nomes** (`Scope.lookup`): sobe a cadeia e, em cada nível, testa primeiro uma propriedade da linha e depois as variáveis. Por isso um campo chamado `data` ou `page` num item de tabela vence a variável de mesmo nome. Se nada casar, `page`/`pages` viram `PageRef`; senão, é erro com a lista dos nomes disponíveis.

**Cadeia montada pelo `Evaluator`:**

```
parâmetros (params/param/cada parâmetro)      ← raiz; mapa tolerante (Values.LenientMap): ausente = null
  └ 1º registro (só se os dados são lista)     ← campos soltos fora de tabela, como no Jasper
      └ dados: vars {rows, data}, itens = rows
          └ tabela: itens = linhas da tabela
              └ grupo: vars {groupKey}, itens = linhas do grupo
                  └ linha: row = item, vars {index}
```

**Agregações**: `sum(valor)` avalia `valor` em cada item de `Scope.aggregateItems()` (o nível mais próximo que tem itens). `sum(itens, x)` usa a coleção dada. `sum(itens)` com `itens` sendo uma coleção visível agrega os próprios elementos (heurística em `Functions.aggregate`).

**Números** são sempre `BigDecimal` na aritmética, sem erro de ponto flutuante nos totais. `Values.normalize` tira os zeros de escala.

### `render`: da árvore ao arquivo

**`Evaluator`**: `ReportTemplate` + dados → `Node.Document`.
- Percorre as bandas (`firstPageHeader`, `pageHeader`, `body`, `pageFooter`).
- Aplica `visible`.
- Avalia os textos, resolve imagens para data URI e gera os códigos de barras (`Barcodes`, ZXing → PNG).
- **Tabelas**: ordena (`sortBy`), agrupa com `LinkedHashMap` (mantém a ordem de chegada, sem exigir dados ordenados) e emite `DataRow`, `GroupHeader` e `TotalRow` já com os valores calculados.
- **`include`**: busca o outro template pelo resolvedor recebido (`Prelo::template`), monta uma cadeia de escopos nova para ele e avalia o corpo dele (com limite de profundidade).
- Cada falha é reembrulhada em `ReportException` com o caminho (`body[3] (table) > sub.body[0]...`).

**`Node`**: árvore imutável já resolvida; nenhuma expressão sobra. Ela desacopla os formatos de saída: HTML/PDF e XLSX consomem a mesma árvore. `Cell` guarda o texto formatado e o **valor cru**, que o XLSX usa para gravar número e data de verdade.

**`HtmlRenderer`**: `Node` → XHTML com CSS de mídia paginada.
- Cabeçalho e rodapé viram *running elements* (`position: running(...)` + `@page { @top-center { content: element(...) } }`), com `@page :first` para o cabeçalho da 1ª página.
- Tabelas usam `-fs-table-paginate: paginate`, o que repete o `thead` em cada página.
- O número de página tem três modos (`PageNumbers`):
  - `CSS`: `counter(page)`, o normal;
  - `SLOTS`: um espaço vazio de largura fixa;
  - `PROBE`: o espaço com um marcador (§ ¶).
  Os dois últimos existem para o modo em blocos.
- O CSS base fica em `BASE_CSS`. O `style` dos blocos passa por `Css.of` (atalhos como `bold`/`size`, camelCase → kebab, números → pt).

**`PdfRenderer`**: wrapper do openhtmltopdf (`useFastMode`, fontes). `measure()` faz só o layout e devolve a página e a posição de cada elemento com `id` com um prefixo dado; é a base de `PageTotals` e `ChunkedPdf`.

**`PageTotals`** ("a transportar"): o HTML não sabe onde a página quebra, então:
1. Mede a paginação da tabela.
2. Empacota as linhas em páginas reservando espaço para as linhas de total.
3. Insere `PageTotalRow` (carry com `page-break-after`, brought em seguida).
4. Mede de novo para conferir.
5. Se uma página estourou, reduz a capacidade e repete (até 6 vezes).

**`ChunkedPdf`** (tabelas maiores que `chunkRows`): o openhtmltopdf guarda o documento inteiro em memória, então:
1. **Passe 1**: faz o layout de blocos de N linhas e corta cada bloco antes da última página. Assim cada bloco termina numa quebra real, e as linhas que sobraram vão para o próximo.
2. **Passe 2**: gera cada bloco em arquivo temporário com espaços reservados para os números, junta tudo com `PDFMergerUtility` em disco e carimba "página X de Y" com PDFBox. A posição dos espaços vem de uma página de prova (`probe`) renderizada com os marcadores.
3. O conteúdo antes da tabela só vai no 1º bloco, e o depois dela só no último.

O teste `ChunkedTest` garante que o texto de cada página é igual ao do modo normal.

**Blocos de "conteúdo gerado"** (todos viram `Node`s já resolvidos, então HTML/PDF/XLSX não sabem que existiram):
- **`chart`** → `Charts` (Java2D, sem dependência nova) desenha um PNG em 3 px/pt e vira `Node.Image`. O `Evaluator` agrupa os itens por `label` (soma por categoria, na ordem de chegada após `sortBy`); `limit` corta ou, em pizza/rosca, junta o resto em "Outros".
- **`crosstab`** → `Node.Table` comum (linhas × colunas + totais), então paginação, zebra e CSV/XLSX funcionam sem código novo.
- **`labels`** → `Node.Labels` (itens × cópias). O `HtmlRenderer` emite uma `<table>` de células de tamanho fixo por folha (`rowsPerPage`), com `page-break-after`; o recuo interno fica num `div` `box-sizing: border-box` porque o `td` do openhtmltopdf soma padding à altura.
- **`markdown`** → `Node.RichText`. As expressões viram marcadores `\uE000n\uE001` (uso privado: sobrevivem ao `trim`) antes do `Markdown.toXhtml`; os valores entram escapados depois. Assim um valor com `*` ou `<` nunca vira marcação.
- **Estilo condicional**: `Evaluator.dynCss` interpola `style` com `${}` por bloco; no `style` de coluna, por linha (`Node.Cell.css`). Propriedades que ficam vazias são removidas.

**`PdfPost`**: pós-processamento PDFBox único, chamado pelo modo normal e pelo `ChunkedPdf`: marca d'água, `metadata` (Info do PDF) e `security` (`StandardProtectionPolicy` AES-256; sem `ownerPassword`, usa um aleatório).

**`CsvRenderer`**: só a 1ª `Node.Table` (procura dentro de caixas, colunas e listas). `;`, UTF-8 com BOM, números sem milhar e com vírgula (Excel pt-BR), CPF/CNPJ como texto.

**`expr/BrCodes`**: `pix()` monta o BR Code (EMV, campos 00/26/52/53/54/58/59/60/62 e CRC16-CCITT no 63); `boleto()` converte linha digitável (47 ou 48 dígitos) em código de barras de 44.

**`Watermarks`**: o openhtmltopdf ignora `opacity` e `z-index`. Por isso a marca d'água é desenhada depois, com PDFBox: `PREPEND` (por baixo do conteúdo), transparência via `PDExtendedGraphicsState`, Helvetica base-14.

**`XlsxRenderer`**: percorre o mesmo `Node`.
- Tabelas viram linhas tipadas (número, data, booleano) com formato de célula.
- Textos e fichas viram linhas simples.
- Cabeçalho e rodapé de página, imagens e linhas são ignorados.
- A largura das colunas é estimada pelo tamanho do conteúdo.

**`ReportRenderer`**: orquestra o fluxo. Carrega os `FontSource`, aplica a família padrão, e para PDF escolhe entre blocos, `PageTotals` e normal, aplicando a marca d'água no fim.

### `check`: FieldCheck

Confere as expressões de um template contra os `fields` declarados, **sobre a árvore JSON do Jackson**, e não sobre o `ReportTemplate`. Assim tolera um template incompleto enquanto o usuário digita.
- Reimplementa as regras de escopo do `Evaluator` em versão estática: o nó `FNode` tem nome, tipo e filhos (filhos `null` = desconhecido, aceita qualquer coisa).
- `Walker` percorre as bandas e registra o escopo de cada caminho JSON (`ctxByPath`), o que serve ao autocomplete.
- `ExprCheck` visita a AST do `Parser`.
- `complete()` responde "que nomes existem aqui?" dado o caminho e o texto até o cursor.

Consumidores: o anotador e o autocomplete do plugin, e o designer (via `DesignerController`, mensagem `problems`).

> Ao mudar uma regra de escopo no `Evaluator`, mude também no `FieldCheck`. Os testes `FieldCheckTest` validam que os exemplos reais não geram avisos.

### Schema: `prelo-core/src/main/resources/prelo.schema.json`

É o contrato. Ele alimenta:
1. o autocomplete e a validação do JSON no IntelliJ (`PreloSchemaProviderFactory`);
2. o **painel de propriedades do designer**, gerado do schema (`objectForm` em `designer.js`);
3. a documentação ao passar o mouse.

Todo campo novo no model precisa entrar aqui, senão some do designer.

---

## prelo-intellij (plugin)

Kotlin; SDK = IDE local (`ideaLocalPath`). As integrações opcionais ficam em `depends optional` com config files próprios:
- `prelo-json.xml`: schema, anotador, autocomplete, intention (precisa do plugin JSON);
- `prelo-jcef.xml`: vazio; só garante as classes JCEF no 2026.2+;
- `prelo-java.xml`: vazio; libera as classes PSI Java para `JavaClassFields`.

As classes que tocam APIs opcionais ficam isoladas (`DesignerBrowser`, `JavaClassFields`) e são chamadas dentro de `try/catch (NoClassDefFoundError)`.

| Parte | Arquivos | Como funciona |
|---|---|---|
| Editor "Prelo" | `ReportEditorProvider`, `ReportPanel`, `PreviewEngine`, `PreviewInputs` | `TextEditorWithPreview`: texto + painel Swing. `PreviewEngine` (sem dependência da IDE, testável) faz template + sample → PDF → imagens PDFBox. Debounce de 500 ms em alterações do documento e do `.sample.json` |
| Designer | `designer/DesignerEditor`, `DesignerBrowser`, `DesignerController`, `resources/designer/*` | aba própria (`PLACE_AFTER_DEFAULT_EDITOR`); ver abaixo |
| Campos de DTO | `fields/JavaClassFields`, `ClassFields`, `JsonText` | PSI: record, getters (inclui os gerados por Lombok e Kotlin), campos; tipos mapeados; listas por `Iterable<T>`; ciclos e profundidade limitados; `merge` preserva `label`/`sample` |
| JSON | `json/FieldCheckAnnotator` (ExternalAnnotator), `FieldCompletionContributor`, `ImportFieldsIntention`, `JsonPaths` | `JsonPaths` traduz entre o caminho do `FieldCheck` (`["body",3,"value"]`) e o PSI JSON, e entre índice decodificado e texto com escapes |
| Migração | `jrxml/JrxmlMigration` | ação no menu; roda `SubreportHints` nas raízes do projeto, converte, liga (`JrxmlLinker`) e grava ao lado do original |

### Designer (HTML/JS dentro do JCEF)

```
designer.js (window.prelo)                      DesignerController (Kotlin)
──────────────────────────                       ──────────────────────────
ready ───────────────────────────────────────►   envia theme + load{json, schema, fileName}
change{json} ────────────────────────────────►   WriteCommandAction: replaceMinimal(doc)  (undo da IDE)
                                                  + FieldCheck ─► problems{items}
preview ─────────────────────────────────────►   PreviewEngine ─► preview{pages: data URIs}
findClasses{query} / importFields{className} ─►  ClassFields ─► classes / fields
copy{text} / paste ──────────────────────────►   CopyPasteManager ─► clipboard{text}
◄──────── load (quando o texto muda no editor, com debounce; o próprio eco é suprimido pela flag `applying`)
```

- **Transporte**: `JBCefJSQuery` (JS → Kotlin) injetado no `onLoadEnd` como `window.preloHost`; Kotlin → JS por `executeJavaScript("window.prelo.receive(...)")`. O client precisa de `JS_QUERY_POOL_SIZE`, senão o JSQuery falha se o navegador nativo já foi criado.
- **Estado no JS**: o modelo é o próprio objeto JSON (`S.model`). Toda mudança passa por `commit(fn, {merge})`: guarda um snapshot para desfazer, envia `change` e redesenha. `merge` junta digitação seguida num passo só.
- **Serialização**: `serialize()`/`pretty()` gravam objetos curtos numa linha, no mesmo estilo dos exemplos. O `JsonPretty` do conversor e o `JsonText` do plugin seguem a mesma regra.
- **Arrasto**: eventos de mouse (`mousedown/move/up` + `elementFromPoint`), **não** HTML5 DnD, que falha no JCEF com renderização off-screen. Zonas de soltar são `.dz[data-zone=<caminho JSON>]`; blocos são `.blk[data-path=<caminho>]`.
- **Painel de propriedades**: gerado do schema (`objectForm`/`scalarInput`/`arrayEditor`/`styleEditor`). Rótulos em português em `LABELS`. Campos especiais: `dataClass` (`classInput`), `style`.
- **Desenvolvimento fora da IDE**: `dev/designer-demo.html` carrega os mesmos arquivos com um host simulado (`window.preloMockHost`); `python -m http.server` na raiz.

---

## prelo-jrxml (migração)

1. **`JrxmlConverter`**:
   - **Leitura**: decodifica (UTF-8 estrito, senão windows-1252) e faz o parse do DOM sem DTD.
   - **Coordenadas**: junta os elementos de cada banda (achatando `frame`/`elementGroup` com deslocamento de coordenadas) e agrupa em **linhas visuais** por sobreposição vertical (`lines`). Uma linha com vários elementos vira `row`, com larguras em % de `columnWidth`.
   - **Detail**: uma linha só de texto vira `table`, com os cabeçalhos procurados por sobreposição horizontal nas bandas `columnHeader`, `pageHeader` e cabeçalhos de grupo (`findHeaderLine`/`bestColumn`). Mais de uma linha vira `list`.
   - **Grupos e totais**: `groupExpression` vira `by`; rodapés com `$V{soma}` viram `total` da coluna na mesma posição (`collectTotals`).
   - **Sub-relatórios**: `subreport` vira `include`. O nome vem de um literal `.jasper`, das dicas de parâmetro ou do nome mais parecido entre os templates conhecidos.
   - **Avisos** vão para a lista e para comentários `//` no topo do JSON.
2. **`JavaExpr`**: parser de um subconjunto de Java que **gera** expressões do prelo (e não as avalia): métodos comuns → funções, getters → propriedades, `SimpleDateFormat(...).format(x)` → `format(x, '...')`, concatenação com literal → template com texto. O que não conhece lança `Unsupported`, e o conversor registra o aviso.
3. **`SubreportHints`**: regex nos `.java`/`.kt`/`.jsp` por `put("param", ... "Nome.jasper")`.
4. **`JrxmlLinker`**: segunda passada em lote. Os campos do template incluído viram subcampos da lista usada como `data` no pai. O Jasper não informa o tipo do elemento de uma `List`.

Validação: `CorpusTest` com `-Dprelo.jrxml.corpus=...` (arquivos reais fora do repositório).

---

## prelo-server

- `PreloCli`: `render` (dados no formato do `*.sample.json`; sem `-d` usa o sample ao lado), `check` (FieldCheck em arquivos ou pastas, código de saída 1 para CI) e `serve`. Silencia o log do openhtmltopdf (`XRLog`) e do fontbox, salvo com `PRELO_VERBOSE`.
- `PreloServer`: `HttpServer` com pool fixo. Templates por nome da pasta (`cache(false)`: editar vale na hora) ou no corpo. Segurança: `127.0.0.1` por padrão, `Bearer` comparado em tempo constante, nomes validados também no `include` (`confined(TemplateLoader)`), `ResourceResolver` preso à pasta, corpo até 64 MB. O `HttpExchange` é fechado no `finally` (um `try (ex)` fecharia antes do `catch` e a resposta de erro sumiria).

## Integrações

- **Spring**: `PreloAutoConfiguration` (`@AutoConfiguration`, registrada em `META-INF/spring/...AutoConfiguration.imports`), `@ConditionalOnMissingBean`, propriedades `prelo.*` em `PreloProperties`.
- **Quarkus**: `PreloProducer` com `@ConfigProperty` nos parâmetros do método producer. O `beans.xml` (`annotated`) faz o Quarkus indexar o jar, sem precisar de módulo de deployment.

---

## Receitas

### Novo tipo de bloco

1. `model/Element.java`: record + `@JsonSubTypes.Type`.
2. `prelo.schema.json`: `definitions.<tipo>`, o `enum` de `element.type` e o `allOf/if` correspondente.
3. `render/Evaluator.element()`: produzir um `Node` (existente ou novo em `render/Node.java`).
4. Se criou um `Node`: tratar em `HtmlRenderer.node()` e `XlsxRenderer.node()` (e em `PageTotals.children/replace` se ele tiver filhos).
5. `check/FieldCheck.Walker.element()`: quais propriedades são texto com `${}` e quais são expressão pura.
6. `designer.js`: entrada em `TYPES` (paleta + valor inicial), desenho em `body()` e rótulos em `LABELS`.
7. Se existir equivalente no Jasper: `JrxmlConverter.block()`.
8. README (tabela de blocos) e testes.

### Nova função de expressão

- `expr/Functions.java`: `fn(...)` (argumentos já avaliados) ou `agg(...)` (agregação, recebe `Expr`).
- Se for agregação: incluir no conjunto `AGGREGATES` de `FieldCheck`, e em `Evaluator` se puder ser usada como `total` de coluna.
- Se o Jasper tiver equivalente: mapear em `JavaExpr.method()` ou no ramo de chamadas estáticas de `primary()`.
- README (lista de funções).

### Nova propriedade no template

Model + schema (o designer passa a mostrar sozinho) + `Evaluator`. Se ela contiver expressões, também o `FieldCheck`.

---

## Armadilhas conhecidas

- **Java 17 no core** (`options.release = 17`): nada de `switch` com pattern matching; use `instanceof`. O plugin compila com JVM target 21.
- **Build do plugin**: rode o Gradle com o JBR da IDE; nos testes, `idea.load.plugins.id` evita que as extensões da IDE Ultimate quebrem o ambiente de teste.
- **openhtmltopdf**: não aceita `opacity`, `z-index`, `counter-reset` de página nem `transform` de forma confiável. Por isso existem `Watermarks`, `ChunkedPdf.probe/stamp` e o `measure`. A fonte padrão (Helvetica base-14) só tem Latin-1.
- **XHTML obrigatório**: o `HtmlRenderer` precisa gerar XML bem formado (`escape()` tira caracteres de controle).
- **Designer ↔ documento**: o designer regrava o JSON inteiro, o que perde comentários `//`; o `replaceMinimal` reduz o diff para o undo. Evite eco: o controller ignora alterações do documento enquanto `applying` está ligado.
- **Regras de escopo duplicadas** entre `Evaluator` (dinâmico) e `FieldCheck` (estático): mantenha as duas em sincronia.
- **Números de página no modo em blocos**: dependem de o rodapé ter o mesmo layout em todas as páginas (a posição é medida uma vez).

## Testes

| Onde | O que cobre |
|---|---|
| `prelo-core` | expressões, formatos, grupos/totais/paginação (lendo o texto do PDF), recursos (1ª página, marca d'água, barras, include, a transportar), equivalência do modo em blocos, FieldCheck; `NovidadesTest` (gráficos, etiquetas, markdown, crosstab, Pix/boleto, senha, CSV, estilo condicional); `GaleriaTest` renderiza `examples/` (`-Dgaleria=true` regrava `docs/galeria`); `PerfTest` opcional |
| `prelo-server` | HTTP (token, nomes, recursos confinados, erros 4xx) e CLI |
| `prelo-fonts` | Unicode no PDF |
| `prelo-jrxml` | tradução de expressões, conversão de um `.jrxml` sintético, lote real opcional |
| `prelo-spring-boot-starter` | auto-config + resposta HTTP |
| `prelo-intellij` | preview, schema/anotador/autocomplete/intention, controller do designer (sync, undo, clipboard), leitura de classes PSI, migração |
| `demo-spring`, `demo-quarkus` | ponta a ponta com a lib publicada |

Os testes de PDF extraem o texto com PDFBox (`setSortByPosition(true)`) e salvam PNGs em `prelo-core/build/out` para inspeção visual.
