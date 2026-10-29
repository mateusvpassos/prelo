#!/usr/bin/env bash
# Imprime a seção "## [<versão>]" do CHANGELOG.md (sem o título). Uso: scripts/release-notes.sh 0.1.0
set -euo pipefail
awk -v v="$1" '
  /^## \[/ { on = index($0, "[" v "]") > 0; next }
  /^\[[^]]+\]: / { next }
  on
' CHANGELOG.md | sed -e '/./,$!d'
