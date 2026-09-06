# Guide & FAQ

Claude/Codex Sub Autocomplete provides native inline suggestions in IntelliJ IDEA,
PyCharm, and Android Studio through your installed Claude Code or Codex CLI. It
uses your selected subscription and does not require a separate API key.

## Before you start

Use IntelliJ IDEA or PyCharm 2025.3 or newer, or Android Studio Quail 4 (2026.1.4),
with the bundled Terminal plugin enabled and Java 21 or newer. The IDE's bundled
runtime is recommended.

Install and authenticate at least one provider:

- **Claude Code:** run `claude auth login`, then check `claude auth status`.
- **Codex:** run `codex login`, then check `codex login status`.

Open **Settings → Tools → Claude/Codex Sub Autocomplete**, choose your provider and
model, and run **Connection Tests and Diagnostics**. API-key authentication is
not supported. Starting with 0.6.5, plugin installation, updates, and removal
require an IDE restart. Complete the restart before using the updated plugin.

## Editor completions

Automatic completion is enabled on a fresh installation. Start typing to request
expressions, multiline blocks, or comment-directed implementations as inline ghost
text. Review a suggestion and press **Tab** to accept it or **Escape** to dismiss it.

Updates preserve saved automatic, hotkey-only, and disabled preferences. Choose
**Automatic typing completions → Off — hotkey only** to use manual requests only.
Press **Alt + Backslash** to request a manual completion.

The status-bar entry shows the selected provider and current activity. Its menu
opens diagnostics and settings. Provider failures are reported directly; the
plugin does not silently switch providers, models, or billing paths.

## Terminal commands

In the IDE's Classic or Reworked terminal, start an empty shell prompt with a
request such as `# list the ten largest TypeScript files`, then press **Tab**.
The shell must be idle; the Reworked terminal also requires shell integration.

The provider replaces the request with one editable command. Review it and press
**Enter** yourself only if it is correct. The plugin never executes generated
commands automatically. Requests and results must fit on one physical line.
Ordinary Tab behavior is preserved in running programs and terminal search fields.

## Context and privacy

Current-file context is bounded. Recent-edit, open-tab, and cross-file context are
separately opt-in and disabled by default. The plugin does not send the whole
repository or Git history.

Requests go through the selected provider's local CLI. This project operates no
proxy or telemetry service. Provider tools, project inspection, file writes, and
command execution are disabled. Common credential patterns are redacted before
requests are sent; diagnostics record operational metadata instead of source or
prompts.

Read the [Privacy Policy](https://github.com/kkoemets/claude-codex-sub-autocomplete/blob/v0.6.5/PRIVACY.md)
and [End User License Agreement](https://github.com/kkoemets/claude-codex-sub-autocomplete/blob/v0.6.5/EULA.md).

## Limitations and troubleshooting

Uncached suggestions can take several seconds. Automatic requests consume
subscription allowance, and quality depends on the selected model and context.
Related cross-file suggestions are read-only previews.

- **No suggestion:** select **Enable Claude/Codex completions**, try **Alt + Backslash**, and
  open diagnostics. Check that the IDE can find the authenticated provider CLI.
- **Login fails:** check the provider's login status and restart the IDE if the CLI
  was installed or authenticated after startup.
- **Terminal Tab does nothing:** select **Enable terminal commands (# request + Tab)**, wait for a ready shell
  prompt, and start the input with `#` followed by at least three descriptive
  characters. Check the terminal integration status in diagnostics.
- **Incomplete response:** review **Approximate completion size (tokens)**. Structurally unsafe
  or incomplete responses are rejected rather than inserted as partial code.

On macOS, Android Studio Quail 4 has a known built-in Reworked-terminal/C++ highlighter lock
exception that can appear while typing, including with this plugin absent.
Report other unexpected IDE errors with privacy-safe diagnostic details.

For supported versions and API limitations, see
[IDE compatibility](https://github.com/kkoemets/claude-codex-sub-autocomplete/blob/v0.6.5/docs/compatibility.md).
For help, read the [full documentation](https://github.com/kkoemets/claude-codex-sub-autocomplete#readme)
or [open an issue](https://github.com/kkoemets/claude-codex-sub-autocomplete/issues).
