# Publicar uma versão

O workflow [`release.yml`](.github/workflows/release.yml) roda numa tag `v*` e faz:

1. o build e os testes;
2. a publicação das bibliotecas no Maven Central;
3. a geração do jar do servidor e do plugin;
4. a publicação no JetBrains Marketplace;
5. a criação da GitHub Release com os dois arquivos.

## Preparação (uma vez)

### Maven Central

1. Entre em <https://central.sonatype.com> com a conta do GitHub. O namespace `io.github.mateusvpassos` é verificado automaticamente.
2. Em *View Account → Generate User Token*, gere um token. Ele tem um usuário e uma senha.
3. Gere uma chave GPG e publique a chave pública:
   ```bash
   gpg --quick-gen-key "Mateus Passos <seu-email>" rsa4096 sign 2y
   gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
   gpg --armor --export-secret-keys <ID>     # conteúdo do secret SIGNING_KEY
   ```

### JetBrains Marketplace

1. Envie o **primeiro** zip do plugin à mão em <https://plugins.jetbrains.com/plugin/add>. O zip sai de `./gradlew :prelo-intellij:buildPlugin`.
2. Espere a aprovação, que leva de 1 a 2 dias úteis.
3. Em *Profile → My Tokens*, gere um token. Ele é usado nas próximas versões.

### Secrets do repositório

Cadastre em *Settings → Secrets and variables → Actions*:

| Secret | Valor |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | usuário do token do Central |
| `MAVEN_CENTRAL_PASSWORD` | senha do token do Central |
| `SIGNING_KEY` | chave privada GPG em formato ASCII armored |
| `SIGNING_KEY_PASSWORD` | senha da chave GPG |
| `JETBRAINS_TOKEN` | token do Marketplace (opcional: sem ele, o passo é pulado) |

## A cada versão

1. Em `CHANGELOG.md`, mova o que está em **Unreleased** para uma seção `## [0.1.0] - AAAA-MM-DD` (na 0.1.0, só troque `unreleased` pela data). O workflow para se a seção não existir, e o texto dela vira as notas da Release.
2. Crie a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

A versão vem da tag (`-PpreloVersion=0.1.0`). Fora de uma release, o build usa `0.1.0-SNAPSHOT`.

Uma versão publicada no Maven Central não pode ser removida nem sobrescrita. Se algo sair errado, publique uma nova versão.

O app desktop (`prelo-desktop`) não entra no workflow. Gere o instalador no Windows com `npm run dist` e anexe à Release.
