# Changelog

All notable changes to Prelo. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - unreleased

First public release.

### Added
- `prelo-core`: declarative `*.report.json` templates rendered to PDF, XLSX, HTML and CSV; tables with groups, subtotals and carried-forward page totals; lists, fields, rows, boxes, images, charts, crosstabs, labels, Markdown, barcodes and QR codes, Pix and boleto helpers, password-protected PDFs, chunked rendering for large reports.
- `prelo-fonts`: DejaVu Sans as the default font for full Unicode.
- `prelo-jrxml`: JasperReports `.jrxml` to `.report.json` converter.
- `prelo-spring-boot-starter` and `prelo-quarkus` integrations.
- `prelo-server`: `render` and `check` commands and an HTTP service.
- `prelo-intellij`: visual designer, live preview, fields from a DTO class, `.jrxml` migration.
- `prelo-desktop`: Windows app with the same designer and a bundled Java runtime.

### Security
- Desktop app on Electron 44.10.0 (fix for compromised eMudhra intermediate certificates).

### Fixed
- Desktop app: the bundled Java runtime lacked locale data, so numbers, currency and month names were formatted in English.
- ISO date-time strings in sample data are converted to date-times.
- Barcodes wider than their column (such as a 103 mm boleto) ignored the requested width.
- `labels` without `width` overflowed an A4 page; they now split the page width.
- Long words without spaces overflowed the page instead of wrapping.

[Unreleased]: https://github.com/mateusvpassos/prelo/commits/main
