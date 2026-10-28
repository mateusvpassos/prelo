# Prelo

English · [Português](README.pt-BR.md)

PDF and XLSX reports for Java (Spring, Quarkus or plain Java) from a declarative JSON template, with an IntelliJ plugin that previews the report while you edit. A lightweight alternative to JasperReports.

- **`*.report.json` templates**: page header and footer, tables with groups, subtotals and grand total, master-detail lists, detail cards, images and columns.
- **Real pagination**: table header repeated on every page, `Page X of Y`, page break per group.
- **Expressions**: `${customer.name}`, `${sum(amount)}`, `${qty * price}`, `${active ? 'Yes' : 'No'}`.
- **Outputs**: PDF (OpenHTMLtoPDF/PDFBox), typed XLSX (real numbers and dates), HTML and CSV.
- **Beyond listings**: charts, crosstabs, labels and badges, Markdown contracts, Brazilian Pix QR codes and boletos, password-protected PDFs. See the [gallery](docs/GALERIA.md).
- **Permissive dependencies**: LGPL (openhtmltopdf) and Apache 2 (PDFBox, fastexcel, Jackson).

| Module | What it is |
|---|---|
| `prelo-core` | the engine (Java 17+, no framework) |
| `prelo-fonts` | optional: DejaVu Sans (full Unicode) as the default font |
| `prelo-jrxml` | converts JasperReports `.jrxml` to `.report.json` (CLI, also used by the plugin) |
| `prelo-spring-boot-starter` | `Prelo` bean and `ReportResponses` for controllers |
| `prelo-quarkus` | CDI producer (`@Inject Prelo`) and JAX-RS `ReportResponses` |
| `prelo-server` | command line (`render`, `check`) and HTTP service to render reports from any language |
| `prelo-intellij` | plugin: visual designer, JSON editor with live preview, fields from a DTO, `.jrxml` migration |
| [`prelo-desktop`](prelo-desktop) | Windows app (Electron) with the same designer and a bundled Java runtime, no IDE needed |

![Prelo desktop](docs/desktop/lado-a-lado.png)

> The plugin, desktop app and default formats target Brazilian Portuguese (`pt-BR` locale, `dd/MM/yyyy`, CPF/CNPJ formats). The locale, time zone and date patterns are configurable. The UI is not translated yet.

## Install

Gradle:
```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.mateusvpassos:prelo-spring-boot-starter:0.1.0") // Spring
    // or prelo-quarkus (Quarkus) / prelo-core (any Java)
    // optional: implementation("io.github.mateusvpassos:prelo-fonts:0.1.0")  // full Unicode
}
```

Maven:
```xml
<dependency>
  <groupId>io.github.mateusvpassos</groupId>
  <artifactId>prelo-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Development version: `./gradlew publishToMavenLocal` publishes `0.1.0-SNAPSHOT` to `~/.m2` (add `mavenLocal()`).

IntelliJ plugin: JetBrains Marketplace (*Prelo Reports*), or `./gradlew :prelo-intellij:buildPlugin` and then `Settings → Plugins → ⚙ → Install Plugin from Disk…` with `prelo-intellij/build/distributions/prelo-intellij-0.1.0.zip`.

Step-by-step guide with sample projects (Portuguese): [TUTORIAL.md](TUTORIAL.md). Code layout for contributors: [ARCHITECTURE.md](ARCHITECTURE.md).

## Use

Templates go in `src/main/resources/reports/` (`sales.report.json`).

**Spring**
```java
@RestController
class ReportsController {
    private final Prelo prelo;
    private final SalesService sales;

    @GetMapping("/reports/sales")
    ResponseEntity<byte[]> sales(@RequestParam String period) {
        var report = prelo.report("sales").data(sales.forPeriod(period)).param("period", period);
        return ReportResponses.inline(report, Format.PDF, "sales-" + period);   // or download(...), Format.XLSX
    }
}
```
Properties (Spring and Quarkus) with their defaults:
```properties
prelo.templates=classpath:reports/        # or file:/path/reports (edit without recompiling)
prelo.cache=true                          # false in dev
prelo.locale=pt-BR
prelo.zone=America/Sao_Paulo
prelo.date-pattern=dd/MM/yyyy
prelo.date-time-pattern=dd/MM/yyyy HH:mm
prelo.chunk-rows=4000                     # above this the PDF is rendered in chunks (see "Large reports"); 0 disables
```

**Quarkus**
```java
@Path("/reports")
public class ReportsResource {
    @Inject Prelo prelo;   // configured from application.properties (prelo.templates, prelo.cache...)

    @GET @Path("/sales")
    public Response sales(@QueryParam("format") @DefaultValue("pdf") String format) {
        return ReportResponses.inline(prelo.report("sales").data(service.sales()), Format.of(format), "sales");
    }
}
```
Tested in JVM mode. Native image has not been validated (PDFBox/AWT).

**Plain Java**
```java
Prelo prelo = Prelo.builder().templates(TemplateLoader.classpath("reports/")).build();
byte[] pdf  = prelo.report("sales").data(list).param("period", "09/2026").pdf();
byte[] xlsx = prelo.report("sales").data(list).xlsx();
byte[] csv  = prelo.report("sales").data(list).csv();   // first table
prelo.report("sales").data(list).pdf(outputStream);
```

Accepted data: a `List` or collection (becomes `rows`, the default table source), a single object, records, POJOs with getters, `Map`. JDBC: `Rows.of(resultSet)`.

Template and expression errors point to the block and field: `body[1].columns[0].value: 'amout' not found (...)`.

## Template

```json
{
  "title": "Sales Report",
  "page": { "size": "A4", "orientation": "portrait", "margin": "22mm 12mm 18mm 12mm" },
  "parameters": [ { "name": "period", "type": "string", "sample": "09/2026" } ],
  "fields": [ { "name": "region", "type": "string" }, { "name": "amount", "type": "money" } ],
  "pageHeader": [ { "type": "text", "value": "Sales · ${period}", "style": { "bold": true, "size": 12 } }, { "type": "line" } ],
  "body": [
    { "type": "table", "sortBy": "region, -amount", "zebra": true,
      "groups": [ { "by": "region", "header": "Region: ${region} (${count()})", "footer": true } ],
      "columns": [
        { "header": "#", "value": "${index}", "width": "8%" },
        { "header": "Customer", "value": "${customer.name}" },
        { "header": "Amount", "value": "${amount}", "format": "currency", "total": "sum", "width": "20%" }
      ] }
  ],
  "pageFooter": [ { "type": "text", "value": "Page ${page} of ${pages}", "align": "right" } ]
}
```

Full examples: [`examples/`](examples) and the [gallery](docs/GALERIA.md).

### Blocks (`type`)

| type | Purpose | Main properties |
|---|---|---|
| `text` | text with expressions | `value`, `format`, `align` |
| `table` | paginated table | `source` (default `rows`), `columns[]` (`header value width align format total`), `groups[]` (`by header footer footerLabel pageBreak`), `sortBy`, `zebra`, `grid`, `totalLabel`, `emptyText` |
| `list` | repeats blocks per item (master-detail, cards) | `source`, `as`, `content[]`, `header[]`, `footer[]`, `separator`, `keepTogether`, `pageBreakBetween` |
| `fields` | label: value grid | `items[]` (`label value format span`), `columns`, `labelWidth` |
| `row` | side-by-side columns | `columns[]` (`width align content[]`), `gap` |
| `box` | groups with a style / keeps together | `content[]`, `keepTogether` |
| `image` | image | `src` (`classpath:`, file, URL, data URI, `${bytes}`), `width`, `height`, `align` |
| `html` | escape hatch (XHTML) | `value` |
| `barcode` | barcode / QR | `value`, `format` (`qr code128 code39 ean13 ean8 upca itf datamatrix pdf417`), `width`, `height`, `showText` |
| `include` | sub-report (body of another template) | `template`, `data`, `params` |
| `chart` | chart (sharp image in the PDF) | `chartType` (`bar hbar line area pie donut`), `label`, `value` or `series[]`, `title`, `width`, `height`, `format`, `sortBy`, `limit`, `legend`, `showValues`, `colors` |
| `crosstab` | pivot table with totals | `rows`, `columns`, `value`, `aggregate` (`sum count avg min max`), `format`, `rowHeader`, `totals`, `sortColumns` |
| `labels` | labels, badges, cards in a grid | `columns`, `rowsPerPage`, `width`, `height`, `gap`, `padding`, `border`, `copies`, `content[]` |
| `markdown` | long formatted text (contracts, letters) | `value`, `align` |
| `line` `spacer` `pageBreak` | | `color thickness` / `height` |

Every block accepts `style` and `visible` (an expression, e.g. `"visible": "notes != null"`). Style values may be expressions, evaluated per block and, for column styles, per row.

Page features: `watermark`, `firstPageHeader`, carried-forward page totals (`pageTotals`), PDF `metadata` and `security` (passwords and permissions, AES-256).

### Expressions

- Text properties use `${...}`: `"Total: ${sum(amount)}"`. Logical properties (`source`, `visible`, `by`, `sortBy`) work with or without `${}`.
- Null-safe navigation (`customer.address.city`), `items[0]`, `map['key']`.
- Operators: `+ - * / %`, `== != < <= > >=`, `&& || !` (`and or not`), `a ? b : c`, `a ?? b`.
- Variables: `rows`, `data`, `param.x` (or just `x`), `index`, `page`, `pages`, `groupKey`.
- Aggregates (current scope: group, table, list or data): `sum avg min max count first last join`.
- Functions: `format upper lower trim text number len isEmpty coalesce contains replace substr round abs if values keys today now`, plus `pix(...)` and `boleto(...)` for Brazilian payments.

Formats: `currency`, `number`, `integer`, `decimal`, `percent`, `date`, `datetime`, `time`, `cpf`, `cnpj`, `cpfCnpj`, `cep`, `upper`, `lower`, `'Yes|No'` (boolean) or any Java pattern (`#,##0.000`, `MMMM/yyyy`).

## IntelliJ plugin

- **Visual designer**: drag blocks, fields and parameters onto the page header, body and footer; edit properties in a panel generated from the schema; resize columns, multi-select, copy and paste, side-by-side PDF preview. Everything is saved to the `.report.json`, and IDE undo works.
- **Fields from a class** (`"dataClass": "com.acme.SaleDTO"`): reads records, POJOs, Lombok and Kotlin, then completes `${...}` by context and warns about unknown fields.
- **Text editor**: any `*.report.json` opens with a live preview; schema-based completion and validation; sample data from `name.sample.json` or generated from `fields`; export to PDF, XLSX and HTML.

## Command line and HTTP service (`prelo-server`)

```bash
./gradlew :prelo-server:fatJar
java -jar prelo-server-all.jar render examples/cobranca.report.json           # uses cobranca.sample.json, writes cobranca.pdf
java -jar prelo-server-all.jar check src/main/resources/reports               # exits 1 on problems (CI)
java -jar prelo-server-all.jar serve --port 8090 --templates reports --token secret
```

```bash
curl -X POST "http://127.0.0.1:8090/render/sales?format=pdf" -H "Authorization: Bearer secret" \
  -H "Content-Type: application/json" -d '{"params": {"period": "09/2026"}, "data": [ ... ]}' -o sales.pdf
```
The service listens on `127.0.0.1` by default. To expose it, use `--host 0.0.0.0` **with** `--token` (or `PRELO_TOKEN`). Relative image paths are confined to the templates folder. `http(s)://` images are downloaded by the renderer, so do not let untrusted data choose `src`.

## Large reports

Tables above `chunkRows` rows (default 4000) render the PDF in chunks that are merged on disk, with the same page breaks, headers, totals and page numbers. 50,000 rows (~1,100 pages) take about 9 s with less than 256 MB of heap.

## Migrating from JasperReports

Right-click a `.jrxml` file or folder in IntelliJ and choose the convert action, or run it in batch:
```bash
./gradlew :prelo-jrxml:run --args="<jrxml folder> <output folder> <java source folder>"
```
Bands become flowing blocks, details become tables or lists, groups and `Sum/Count/Average` variables become groups and totals, sub-reports become `include`, and Java expressions are translated. Anything not converted is listed in `//` comments at the top of the file. On 184 real-world `.jrxml` files, 183 converted and 95% rendered directly with sample data.

## Build and test

```bash
./gradlew build
```
Needs JDK 21 (the libraries target Java 17). The plugin builds against the local IDE in `ideaLocalPath` when it exists, otherwise it downloads `ideaVersion` (see `gradle.properties`).

Releases: [RELEASING.md](RELEASING.md).

## License

Apache License 2.0 ([LICENSE](LICENSE)). Third-party components are listed in [NOTICE](NOTICE).
