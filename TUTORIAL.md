# Tutorial do relato

## 0. O que tem aqui

Tudo fica em `C:\Users\mvini\Documents\relato` (GitHub privado `mateusvpassos/relato`):

```
relato/
├── relato-core/                  engine (a lib que vai no seu sistema)
├── relato-spring-boot-starter/   integração Spring
├── relato-intellij/              plugin (designer + preview)
├── demo-spring/                  ← PROJETO DE TESTE (Spring Boot usando a lib)
│   └── src/main/resources/reports/
│       ├── vendas.report.json    relatório com grupos, subtotais e total
│       ├── pedidos.report.json   mestre-detalhe (pedido + itens)
│       └── pedidos.sample.json   dados de exemplo para o preview
├── examples/                     os mesmos templates, soltos
└── dev/designer-demo.html        designer fora da IDE (para desenvolver o próprio designer)
```

## 1. Instalar o plugin no IntelliJ (uma vez)

1. Gere o zip (ou use o que já está gerado):
   ```bash
   cd C:/Users/mvini/Documents/relato
   JAVA_HOME="C:/Users/mvini/AppData/Local/Programs/IntelliJ IDEA/jbr" ./gradlew :relato-intellij:buildPlugin
   ```
2. No IntelliJ, vá em **Settings → Plugins → ⚙ → Install Plugin from Disk…** e escolha
   `relato/relato-intellij/build/distributions/relato-intellij-0.1.0.zip`.
3. Reinicie a IDE.

## 2. Publicar a lib no Maven local (uma vez, e de novo sempre que mudar a lib)

```bash
cd C:/Users/mvini/Documents/relato
JAVA_HOME="C:/Users/mvini/AppData/Local/Programs/IntelliJ IDEA/jbr" ./gradlew publishToMavenLocal
```
Isso copia a lib para `C:\Users\mvini\.m2\repository\io\github\mateusvpassos\`. Qualquer projeto Gradle (com `mavenLocal()`) ou Maven da máquina passa a enxergá-la.

## 3. Rodar o projeto de teste

1. No IntelliJ, abra **File → Open…** em `relato/demo-spring` (abra como projeto separado).
2. Espere o Gradle sincronizar e rode `DemoApplication` (▶ ao lado do `main`).
3. Abra <http://localhost:8085>. Os links geram vendas e pedidos em PDF, XLSX e HTML.

O que acontece por trás (`RelatoriosController.java`):
```java
var report = relato.report("vendas")      // lê reports/vendas.report.json
        .data(dados.vendas(180))          // lista de objetos → linhas da tabela
        .param("periodo", periodo);       // ${periodo} no template
return ReportResponses.inline(report, Format.PDF, "vendas");
```

## 4. Editar um relatório existente

1. Abra `demo-spring/src/main/resources/reports/vendas.report.json`. Embaixo do editor há duas abas:
   - **Relato**: o JSON à esquerda e o PDF à direita, atualizando enquanto você digita.
   - **Designer**: o editor visual.
2. Na aba **Designer**:
   - **Mover**: arraste um bloco da página para outro lugar (a linha azul mostra onde ele cai).
   - **Adicionar**: arraste da paleta à esquerda (Texto, Tabela, Linha...).
   - **Configurar**: clique no bloco; as propriedades aparecem à direita. Numa tabela, clique no cabeçalho de uma coluna para editar aquela coluna.
   - **Texto rápido**: duplo clique no texto.
   - **Ver o PDF de verdade**: botão **▶ Preview PDF**.
3. Salve (Ctrl+S) e recarregue o PDF no navegador. O demo usa `relato.cache=false` e lê a pasta direto, então não precisa reiniciar.

## 5. Criar um relatório novo do zero (exercício)

Vamos fazer um relatório de **produtos por categoria**.

**5.1 Criar o arquivo**
Clique com o botão direito na pasta `reports`, escolha **New → Relatório Relato** e digite `produtos`. O arquivo já vem com cabeçalho, uma tabela e o rodapé com "Página X de Y".

**5.2 Declarar os campos** (servem para arrastar e para gerar dados de exemplo)
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
Na aba **Relato**, clique no botão **Dados de exemplo** da barra do preview. Ele cria o `produtos.sample.json`; edite os valores como quiser. O preview passa a usá-lo.

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
    return ReportResponses.inline(relato.report("produtos").data(lista), Format.of(formato), "produtos");
}
```
Reinicie o app e abra <http://localhost:8085/relatorios/produtos>. Os nomes dos campos do template precisam bater com os do record/getter (`nome`, `preco`...).

## 6. Usar num sistema de verdade

**Gradle**
```kotlin
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("io.github.mateusvpassos:relato-spring-boot-starter:0.1.0-SNAPSHOT") }
```
**Maven**
```xml
<dependency>
  <groupId>io.github.mateusvpassos</groupId>
  <artifactId>relato-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
- Sem Spring (Quarkus, Java puro): use `relato-core` e `Relato.builder()...build()` (exemplo no README).
- Os templates ficam em `src/main/resources/reports/`.
- Em produção use `relato.templates=classpath:reports/` e `relato.cache=true`, que já são o padrão.
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
- **O projeto não acha `relato-spring-boot-starter`**: rode o passo 2 e confira se `mavenLocal()` está nos repositórios.
- **Mudei a lib e o projeto não viu**: rode `publishToMavenLocal` de novo e dê *Reload Gradle Project* no IntelliJ.
- **A aba Designer não aparece**: o plugin está instalado? A IDE foi reiniciada? O arquivo termina em `.report.json`?
- **O Designer fica em "Carregando…"**: abra *Help → Show Log in Explorer* e procure `relato` no `idea.log`.
- **Caractere virou `#` no PDF**: a fonte padrão (Helvetica) não tem esse símbolo. Registre uma fonte TTF com `Relato.builder().font(...)`.
