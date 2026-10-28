# Tutorial do prelo

## 0. O que tem aqui

Tudo fica no repositório [`mateusvpassos/prelo`](https://github.com/mateusvpassos/prelo):

```
prelo/
├── prelo-core/                  engine (a lib que vai no seu sistema)
├── prelo-fonts/                 opcional: fonte com Unicode completo (✓ ★ Ω, cirílico...)
├── prelo-spring-boot-starter/   integração Spring
├── prelo-quarkus/               integração Quarkus
├── prelo-jrxml/                 migração de relatórios JasperReports (.jrxml)
├── prelo-intellij/              plugin (designer, preview, campos de DTO, migração)
├── demo-spring/                  ← PROJETO DE TESTE Spring Boot (porta 8085)
│   └── src/main/resources/reports/
│       ├── vendas.report.json    relatório com grupos, subtotais e total
│       ├── pedidos.report.json   mestre-detalhe (pedido + itens)
│       └── pedidos.sample.json   dados de exemplo para o preview
├── demo-quarkus/                 ← PROJETO DE TESTE Quarkus (porta 8086)
├── examples/                     templates de exemplo, soltos
└── dev/designer-demo.html        designer fora da IDE (para desenvolver o próprio designer)
```

## 1. Instalar o plugin no IntelliJ (uma vez)

1. Gere o zip (ou use o que já está gerado):
   ```bash
   cd prelo
   ./gradlew :prelo-intellij:buildPlugin
   ```
2. No IntelliJ, vá em **Settings → Plugins → ⚙ → Install Plugin from Disk…** e escolha
   `prelo/prelo-intellij/build/distributions/prelo-intellij-0.1.0.zip`.
3. Reinicie a IDE.

## 2. Publicar a lib no Maven local (uma vez, e de novo sempre que mudar a lib)

```bash
cd prelo
./gradlew publishToMavenLocal
```
Isso copia a lib para `~/.m2/repository/io/github/mateusvpassos/`. Qualquer projeto Gradle (com `mavenLocal()`) ou Maven da máquina passa a enxergá-la.

## 3. Rodar o projeto de teste

1. No IntelliJ, abra **File → Open…** em `prelo/demo-spring` (abra como projeto separado).
2. Espere o Gradle sincronizar e rode `DemoApplication` (▶ ao lado do `main`).
3. Abra <http://localhost:8085>. Os links geram vendas e pedidos em PDF, XLSX e HTML.

Quarkus: abra `prelo/demo-quarkus` e rode, no terminal dessa pasta, o comando abaixo. Depois abra <http://localhost:8086/relatorios/vendas> (`?formato=xlsx` ou `?formato=html`).
```bash
./gradlew quarkusDev
```

O que acontece por trás (`RelatoriosController.java`):
```java
var report = prelo.report("vendas")      // lê reports/vendas.report.json
        .data(dados.vendas(180))          // lista de objetos → linhas da tabela
        .param("periodo", periodo);       // ${periodo} no template
return ReportResponses.inline(report, Format.PDF, "vendas");
```

## 4. Editar um relatório existente

1. Abra `demo-spring/src/main/resources/reports/vendas.report.json`. Embaixo do editor há duas abas:
   - **Prelo**: o JSON à esquerda e o PDF à direita, atualizando enquanto você digita.
   - **Designer**: o editor visual.
2. Na aba **Designer**:
   - **Mover**: arraste um bloco da página para outro lugar (a linha azul mostra onde ele cai).
   - **Adicionar**: arraste da paleta à esquerda (Texto, Tabela, Linha...).
   - **Configurar**: clique no bloco; as propriedades aparecem à direita. Numa tabela, clique no cabeçalho de uma coluna para editar aquela coluna.
   - **Texto rápido**: duplo clique no texto.
   - **Ver o PDF de verdade**: botão **▶ Preview PDF**; ou **◧ Lado a lado** para ver o PDF atualizando enquanto edita.
   - **Largura das colunas**: arraste a borda entre dois cabeçalhos da tabela.
   - **Vários blocos**: Ctrl+clique seleciona mais de um; Ctrl+C/Ctrl+V copia e cola, inclusive entre relatórios.
3. Salve (Ctrl+S) e recarregue o PDF no navegador. O demo usa `prelo.cache=false` e lê a pasta direto, então não precisa reiniciar.

## 5. Criar um relatório novo do zero (exercício)

Vamos fazer um relatório de **produtos por categoria**.

**5.1 Criar o arquivo**
Clique com o botão direito na pasta `reports`, escolha **New → Relatório Prelo** e digite `produtos`. O arquivo já vem com cabeçalho, uma tabela e o rodapé com "Página X de Y".

**5.2 Declarar os campos** (servem para arrastar, para o autocomplete e para gerar dados de exemplo)

*Jeito rápido, a partir da classe:* crie antes o record `Produto` do passo 5.5. No Designer, clique em **⚙ Relatório**, digite `Produto` em *Classe dos dados* (a lista sugere as classes do projeto) e clique em **⇩ Importar campos da classe**. No editor de texto dá para fazer o mesmo: escreva `"dataClass": "demo.Produto"` e use **Alt+Enter → Importar campos da classe**.

A partir daí:
- `${` no texto sugere os campos certos para o ponto onde você está. Dentro de uma tabela `source: "itens"`, os campos do item aparecem primeiro.
- Um campo que não existe fica sublinhado em amarelo no JSON e marcado com ⚠ no Designer.

*Jeito manual:*
Na aba **Designer**, clique em **⚙ Relatório**, vá em *Campos dos dados* e use **+ adicionar**. Deixe assim:

| Nome | Tipo |
|---|---|
| codigo | integer |
| nome | string |
| categoria | string |
| estoque | integer |
| preco | money |

Os campos aparecem na paleta, em *Campos dos dados*.

**5.3 Montar a tabela**
- Selecione a tabela e apague as colunas que não quiser (✕ no card da coluna).
- Arraste `categoria`, `nome`, `estoque` e `preco` da paleta **para cima da tabela**. Cada um vira uma coluna, e `preco` já vem com formato moeda e total.
- Em *Grupos*, clique **+ adicionar** e deixe *Agrupar por* = `categoria`, com *Subtotais no rodapé do grupo* marcado.
- Em *Ordenar por*, escreva `categoria, nome`.
- Clique **▶ Preview PDF**. O PDF usa dados inventados a partir dos campos.

**5.4 Usar dados de exemplo seus (opcional)**
Na aba **Prelo**, clique no botão **Dados de exemplo** da barra do preview. Ele cria o `produtos.sample.json`; edite os valores como quiser. O preview passa a usá-lo.

**5.5 Ligar no sistema**
Crie uma classe no `demo-spring`:
```java
public record Produto(int codigo, String nome, String categoria, int estoque, BigDecimal preco) {}
```
E um endpoint no controller:
```java
@GetMapping("/relatorios/produtos")
public ResponseEntity<byte[]> produtos(@RequestParam(defaultValue = "pdf") String formato) {
    List<Produto> lista = List.of(
        new Produto(1, "Caneta", "Papelaria", 120, new BigDecimal("2.50")),
        new Produto(2, "Caderno", "Papelaria", 40, new BigDecimal("18.90")),
        new Produto(3, "Mouse", "Informática", 15, new BigDecimal("59.90")));
    return ReportResponses.inline(prelo.report("produtos").data(lista), Format.of(formato), "produtos");
}
```
Reinicie o app e abra <http://localhost:8085/relatorios/produtos>. Os nomes dos campos do template precisam bater com os do record/getter (`nome`, `preco`...).

## 6. Usar num sistema de verdade

**Gradle**
```kotlin
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("io.github.mateusvpassos:prelo-spring-boot-starter:0.1.0-SNAPSHOT") }
```
**Maven**
```xml
<dependency>
  <groupId>io.github.mateusvpassos</groupId>
  <artifactId>prelo-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
- Quarkus: use `prelo-quarkus` e injete com `@Inject Prelo prelo` (mesmas propriedades `prelo.*`; ver `demo-quarkus`).
- Java puro: use `prelo-core` e `Prelo.builder()...build()` (exemplo no README).
- Acentos já funcionam. Para símbolos fora do Latin-1 (✓ ★ → Ω, cirílico...), adicione `prelo-fonts`.
- Os templates ficam em `src/main/resources/reports/`.
- Em produção use `prelo.templates=classpath:reports/` e `prelo.cache=true`, que já são o padrão.
- Dados do banco: passe a lista de entidades/DTOs, ou `Rows.of(resultSet)` com JDBC puro.

## 7. Referência rápida de expressões

| Quero | Escrevo |
|---|---|
| campo | `${nome}`, `${cliente.nome}` |
| parâmetro | `${periodo}` ou `${param.periodo}` |
| conta | `${qtd * preco}` |
| total da tabela/grupo | `total: "sum"` na coluna, ou `${sum(valor)}` |
| quantidade de linhas | `${count()}` |
| soma de uma lista do item | `${sum(itens, qtd * preco)}` |
| condição | `${pago ? 'Pago' : 'Pendente'}` |
| mostrar só se | `"visible": "obs != null"` |
| nº da linha | `${index}` |
| página | `Página ${page} de ${pages}` |
| data/hora atual | `${now()}`, `${today()}` |
| formatos | `currency date datetime decimal integer percent cpf cnpj cep 'Sim|Não'` |

## 8. Problemas comuns

- **`'x' não encontrado (disponíveis na linha: ...)`**: o nome no template não bate com o do objeto. A mensagem lista os nomes que existem.
- **O projeto não acha `prelo-spring-boot-starter`**: rode o passo 2 e confira se `mavenLocal()` está nos repositórios.
- **Mudei a lib e o projeto não viu**: rode `publishToMavenLocal` de novo e dê *Reload Gradle Project* no IntelliJ.
- **A aba Designer não aparece**: o plugin está instalado? A IDE foi reiniciada? O arquivo termina em `.report.json`?
- **O Designer fica em "Carregando…"**: abra *Help → Show Log in Explorer* e procure `prelo` no `idea.log`.
- **Caractere virou `#` no PDF**: a fonte padrão (Helvetica) não tem esse símbolo. Adicione a dependência `prelo-fonts` (ou registre uma TTF com `Prelo.builder().font(...)`).
- **"Importar campos da classe" diz que não achou a classe**: use o nome completo (`demo.Dados.Venda`) e espere a IDE terminar de indexar.
- **Relatório muito grande estourando memória**: o PDF já sai em blocos acima de 4000 linhas (`prelo.chunk-rows`). Se a tabela usa `pageTotals`, os blocos ficam desligados.

## 9. Migrar relatórios do Jasper

1. No IntelliJ, clique com o botão direito num `.jrxml` (ou numa pasta cheia deles) e escolha **Converter para Prelo (.report.json)**. O arquivo novo fica ao lado do original.
2. Abra o `.report.json` gerado. Se houver algo para revisar, a lista está em comentários `//` no topo (ex.: expressão que não deu para traduzir, sub-relatório com nome adivinhado).
3. Confira no **▶ Preview PDF**. Sub-relatórios viram blocos `include` e precisam estar na mesma pasta (converta a pasta inteira).
4. No sistema, troque a chamada do Jasper por `prelo.report("nome").data(lista).params(parametros).pdf()`. Os mesmos DTOs e o mesmo mapa de parâmetros servem.

Em lote, pela linha de comando (o 3º argumento é a pasta do código Java, usada para descobrir qual `.jasper` vai em cada parâmetro de sub-relatório):
```bash
./gradlew :prelo-jrxml:run --args="C:/projeto/reports C:/saida C:/projeto/src"
```

## 10. Recursos extras

Os detalhes e exemplos de cada um estão no README (*Recursos de página*):
- `"watermark"`: marca d'água (ex.: CANCELADO).
- `"firstPageHeader"`: cabeçalho diferente na 1ª página.
- `"pageTotals": true` na tabela: "a transportar" / "transporte".
- Blocos `barcode` (QR, code128, EAN...) e `include` (sub-relatório).

No Designer eles aparecem na paleta (*Código de barras / QR*, *Sub-relatório*), em ⚙ Relatório (*Marca d'água*) e nas propriedades da tabela (*A transportar*). O cabeçalho da 1ª página aparece no Designer pelo link *+ cabeçalho diferente na 1ª página*.

## 11. Além de listagens

Abra os exemplos de `examples/` no IntelliJ (o preview usa o `.sample.json` ao lado). Imagens de todos em [docs/GALERIA.md](docs/GALERIA.md).

| Quero... | Use | Exemplo |
|---|---|---|
| um painel com gráficos | bloco `chart` (barra, linha, área, pizza, rosca) e `crosstab` | `painel.report.json` |
| cobrar com Pix e boleto | `${pix(chave, valor, nome, cidade, txid)}` num `barcode` `qr`; `${boleto(linha)}` num `barcode` `itf` | `cobranca.report.json` |
| imprimir etiquetas | bloco `labels` com `columns`, `rowsPerPage`, `width`, `height`, `copies` | `etiquetas.report.json` |
| crachás / cartões | `labels` + `box` com fundo + QR | `crachas.report.json` |
| contrato, carta, certificado | bloco `markdown` com `${...}` no texto | `contrato.report.json` |
| PDF que abre com senha | `"security": { "userPassword": "${cpf}", "allowCopy": false }` | README |
| destacar valores | estilo condicional: `"style": { "color": "${saldo < 0 ? 'red' : ''}" }` | crachás |
| planilha simples | `.csv()` ou *Exportar CSV* no preview | |

No Designer: *Gráfico*, *Tabela cruzada*, *Etiquetas / crachás* e *Texto formatado* na paleta; *QR Pix* e *Boleto (ITF)* em Prontos. Senha e propriedades do PDF ficam em ⚙ Relatório.

## 12. Gerar relatório sem Java (CLI e HTTP)

```bash
./gradlew :prelo-server:fatJar
java -jar prelo-server/build/libs/prelo-server-0.1.0-SNAPSHOT-all.jar render examples/painel.report.json
java -jar prelo-server/build/libs/prelo-server-0.1.0-SNAPSHOT-all.jar serve --templates examples
```
Depois `POST http://127.0.0.1:8090/render/painel?format=pdf` com `{"params": {...}, "data": [...]}`. Detalhes no README (*Linha de comando e serviço HTTP*).
