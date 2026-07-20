# Support

## Before asking for help

1. Confirm you are using IntelliJ IDEA 2026.1 or newer and the latest plugin release.
2. Run `claude auth status` or `codex login status` in a terminal.
3. Open the plugin's status-bar menu and run **Connection Tests and Diagnostics**.
4. Retry with manual completion using `Alt+\`.
5. Review the troubleshooting section in the [README](README.md#troubleshooting).

For terminal commands, confirm **Enable terminal commands (# request + Tab)** is active. Both
classic and Reworked Terminal sessions are supported. The shortcut only takes over when the
current prompt begins with `#` followed by a descriptive request; the plugin inserts a command but
never executes it. Diagnostics should show **Terminal Tab integration attached** after the project
opens.

## Where to ask

- Use a [bug report](https://github.com/kkoemets/claude-codex-sub-autocomplete/issues/new?template=bug_report.yml) for reproducible plugin defects.
- Use a [feature request](https://github.com/kkoemets/claude-codex-sub-autocomplete/issues/new?template=feature_request.yml) for product ideas.
- Use [private vulnerability reporting](https://github.com/kkoemets/claude-codex-sub-autocomplete/security/advisories/new) for security issues.

## Safe diagnostics

Share IntelliJ IDEA version, plugin version, provider, configured model, timing, status, and redacted error text. Never share source code, prompts, tokens, CLI configuration files, authentication output, private file paths, or complete IDE logs.
