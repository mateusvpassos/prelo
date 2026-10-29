# Third-party notices (Prelo desktop)

The Prelo libraries and their dependencies (openhtmltopdf, PDFBox, fastexcel, Jackson, ZXing, DejaVu fonts) are listed in
`NOTICE`. This file covers what the Windows app adds.

## Java runtime

`resources/engine/runtime` is a minimal OpenJDK 21 runtime (Amazon Corretto 21.0.12.1, https://github.com/corretto/corretto-21)
created with `jlink`. It is licensed under the GNU General Public License v2.0 with the Classpath Exception; the license and
the notices of each module are in `resources/engine/runtime/legal/` (the "Textos das licenças" button opens that folder).
The corresponding source code is available at https://github.com/corretto/corretto-21 (tag 21.0.12.1).

## Geist and Geist Mono fonts

The designer uses Geist and Geist Mono — Copyright 2024 The Geist Project Authors (https://github.com/vercel/geist-font),
SIL Open Font License 1.1 (`designer/fonts/OFL.txt`).

## Electron

The installer bundles Electron (MIT) and Chromium. Their licenses are shipped next to the executable as
`LICENSE.electron.txt` and `LICENSES.chromium.html`.
