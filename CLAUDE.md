# Prelo

Relatórios PDF/XLSX para Java a partir de template JSON (`*.report.json`), com plugin IntelliJ de preview. Antigo "relato" (a pasta ainda tem o nome velho). Público, Apache 2.0.

## Comandos
```bash
./gradlew.bat build                      # use o JBR da IntelliJ como JAVA_HOME
./gradlew.bat :prelo-intellij:runIde     # plugin
cd prelo-desktop && npm run dist:dir     # app desktop de teste (%LOCALAPPDATA%\Prelo-teste)
```

## Estrutura
- `prelo-core`: motor. `prelo-spring-boot-starter`, `prelo-quarkus`, `prelo-server`, `prelo-jrxml` (importa Jasper), `prelo-intellij`, `prelo-desktop`, `prelo-fonts`.
- `examples/`, `demo-spring`, `demo-quarkus` (este já falhava antes do rename: guice no mavenLocal).

## Regras
- README em inglês; CHANGELOG.md alimenta as notas da Release (`scripts/release-notes.sh`).
- Release por tag `v*`: só com pedido do usuário. Ainda falta do lado dele: Central Portal, GPG, secrets, Marketplace.
