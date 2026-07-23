# Privacy Policy

Effective July 19, 2026

Claude/Codex Sub Autocomplete does not operate a developer-controlled server and does not collect
analytics, telemetry, account credentials, prompts, source code, or completion results.

## Data processed for completions

When You manually request a completion, or opt in to automatic completion, the Plugin sends a
bounded prompt directly through the Claude Code or Codex CLI installed on Your computer. Depending
on Your settings and the completion location, that prompt can contain:

- code surrounding the caret and the current file name;
- language and editor metadata needed to place the completion;
- bounded snippets from recent edits or open tabs, if those options are enabled; and
- bounded cross-file context, if that separate option is enabled.

The Plugin does not send the whole repository, Git history, deleted text, or every open file.
Common credential patterns are redacted before a request is sent. Provider tools, project
inspection, file writes, and command execution are disabled for completion requests.

When You explicitly type a `# request` and press Tab in IntelliJ's terminal, the Plugin
sends the bounded request, shell name, coarse operating-system family, working directory, project name, and detected project-marker
names. It does not send terminal history, terminal output, environment variables, or project-file
contents for that request. A returned command is inserted for Your review and is never submitted or
executed by the Plugin.

## Where data goes

Requests go from Your computer to the provider selected in the Plugin, through that provider's
installed CLI. The Developer does not receive or proxy the request. Anthropic or OpenAI processes
the request under the terms and privacy controls associated with Your account. Review those
providers' current policies before sending confidential or personal data.

## Local storage and diagnostics

Plugin settings are stored by the IDE. Compatible completion tails can remain in an in-memory cache
for up to two minutes and are discarded when the project closes or relevant settings change.
Diagnostics contain operational events such as provider, model, timing, status, and error details;
they do not contain source code or prompts.

## Your controls

Automatic completion is off by default. Optional recent-edit, open-tab, and cross-file context are
also off by default. Terminal command generation can be disabled independently. You can disable
completions, clear in-memory state by changing settings or closing the project, sign out of a
provider CLI, or uninstall the Plugin.

## Contact

The Developer is Kristjan Koemets. Current contact and source information is available through the
[Developer's GitHub profile](https://github.com/kkoemets) and the JetBrains Marketplace vendor page.
