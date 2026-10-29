# Security

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private reporting: **Security → Report a vulnerability** ([direct link](https://github.com/mateusvpassos/prelo/security/advisories/new)).

Include the Prelo version and module, steps to reproduce and the impact. You will get a first answer within 7 days.

## Scope

Most relevant areas:
- `prelo-server` (HTTP service): authentication bypass, path traversal outside the templates folder, resource exhaustion;
- template evaluation: expressions reaching Java classes or the file system beyond what is documented;
- image `src` handling (local files, `http(s)://` downloads);
- the desktop app and IntelliJ plugin loading untrusted `.report.json` or `.jrxml` files.

Rendering templates written by untrusted users is not a supported use case yet. Treat templates as code.
