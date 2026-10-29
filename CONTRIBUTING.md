# Contributing

Bug reports, ideas and pull requests are welcome.

- **Bug or idea:** open an [issue](https://github.com/mateusvpassos/prelo/issues/new/choose) using a template.
- **Security issue:** follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## Development

Requires JDK 21 (the libraries target Java 17).

```bash
./gradlew build                           # libraries, server and plugin, with tests
./gradlew :prelo-intellij:runIde          # IntelliJ sandbox with the plugin
./gradlew publishToMavenLocal             # 0.1.0-SNAPSHOT in ~/.m2, used by demo-spring/ and demo-quarkus/
```
The plugin downloads the IDE set in `ideaVersion`. To use an installed IDE instead, set `ideaLocalPath=<IDE folder>` in `~/.gradle/gradle.properties`.

Desktop app: see [prelo-desktop/README.md](prelo-desktop/README.md). Code layout: [ARCHITECTURE.md](ARCHITECTURE.md).

## Pull requests

1. For large changes, open an issue first to agree on the approach.
2. Add or update tests, and keep `./gradlew build` green.
3. Follow the existing style. Code comments and the plugin/desktop UI are in Portuguese (pt-BR); public docs are in English.
4. Add an entry to `CHANGELOG.md` under **Unreleased**.

By submitting a pull request you agree that your contribution is licensed under the project's [Apache License 2.0](LICENSE).
