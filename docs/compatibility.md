# IDE compatibility

One plugin ZIP supports these products:

| Product | Supported versions |
| --- | --- |
| IntelliJ IDEA | 2025.3 and newer |
| PyCharm | 2025.3 and newer |
| Android Studio | Quail 4 (2026.1.4) |

The bundled Terminal plugin must be enabled. The plugin requires Java 21 or newer
and IntelliJ Platform build 253 or newer; the IDE's bundled runtime is recommended.
Android Studio uses its own release numbering, which differs from its underlying
IntelliJ Platform version.

## Installation and updates

Install the signed ZIP through **Settings → Plugins → Install Plugin from Disk**,
then restart the IDE. Starting with version 0.6.5, installation, updates, and
removal require a restart.

A fresh installation enables automatic completion. Updates preserve saved
provider, automatic/manual, and disabled settings. Claude Code or Codex must be
installed and authenticated to generate suggestions.

## Compatibility policy

The plugin compiles against the oldest supported SDK and is checked against
maintained IDEA, PyCharm, and Android Studio builds before release. The package
has no artificial upper build cap; future IDE versions still need verification.
Additional products listed automatically by Marketplace are outside the
maintained product matrix.

Deprecated APIs block release. Some terminal and manual-completion integrations
use experimental APIs; their purposes and maintenance risks are documented in
[API stability](api-stability.md). A platform API or behavior change can require a
plugin update.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development and testing, and
[RELEASING.md](../RELEASING.md) for release requirements.
