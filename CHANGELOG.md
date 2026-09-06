# Changelog

All notable user-facing changes are documented here.

## 0.6.5 — Early Access

- Require an IDE restart for plugin installation, updates, and removal, avoiding the platform's unsafe dynamic reload path.
- Remove terminal callbacks when the plugin unloads, preventing old settings classes from handling Tab after reinstalling without an IDE restart.
- Cancel diagnostics work and queued related-edit callbacks, and close plugin dialogs, when the plugin unloads.
- Strengthen release checks for installer restarts, physical keyboard input, saved settings, and IDE runtime errors.
- Keep settings descriptions and executable fields within the available dialog width.
- Replace the deprecated Classic-terminal input getter while preserving queued input, session-change rejection, and native Tab ordering.
- Prevent unavailable working-directory flow values from falling back to a deprecated getter on newer terminal APIs; retain the non-deprecated older API on supported IDEs that still use it.
- Block releases on deprecated APIs and all other verifier failure categories except the documented experimental integrations. Require the full headless gate before Gradle publication.

## 0.6.4 — Early Access

- Enable automatic completion from the selected subscription on first installation while preserving saved automatic/manual and disabled settings.
- Keep terminal process and filesystem checks off the UI thread, preserve native Tab ordering, and reject stale responses after terminal input or output changes. Installed-IDE checks now fail on plugin-related runtime errors.
- Add PyCharm and Android Studio compatibility using the same plugin ZIP, with explicit Plugin Verifier targets and installed-IDE checks for editor suggestions and terminal command review.
- Route physical Tab requests through the focused Reworked terminal view, including newer terminals that do not register a Classic widget.
- Handle terminal requests before native key dispatch, preserving ordinary Tab and search navigation. Reworked generation requires focused editable shell input and rejects running programs and alternate screens, including when a provider response arrives.
- Strengthen installed terminal checks with absolute execution sentinels, editable-input and deliberate-Enter controls, single-Tab child-process probes, and repetition-aware timeouts.
- Reject partial Claude output when its process fails, preserve structured CLI error details, and stop child processes on cancellation, timeout, or output overflow.
- Add subprocess integration checks and an installed Claude CLI compatibility check that needs no active subscription.
- Set GPT-5.6 Luna with low reasoning as the default Codex profile and the pinned live-test profile.
- Route the plugin's manual completion action directly to its provider, even when another inline provider is registered first.
- Clarify that editor completions should satisfy the surrounding function's intent, including nearby comments, while retaining the output and tool restrictions.
- Clarified terminal request scope so a current-directory operation does not acquire a child-directory loop, and container requests are not replaced by package scripts.
- Added an opt-in installed-IDE test using real Claude and Codex subscriptions to verify ghost text and explicit acceptance.

## 0.6.3 — Early Access

- Added verified compatibility with IntelliJ IDEA 2026.2, including the current 2026.2.1 release.
- Kept installation open-ended from IntelliJ IDEA 2025.3 onward while compiling against the oldest supported SDK to prevent accidental use of newer-only APIs.
- Expanded the release-blocking compatibility policy to cover the oldest supported IDE, current stable IDE, and JetBrains-recommended forward builds.
- Replaced a deprecated 2026.2 terminal working-directory call with a cross-version adapter that supports both the old and replacement APIs.

## 0.6.2 — Early Access

- Expanded compatibility to IntelliJ IDEA 2025.3 and newer.
- Added IntelliJ IDEA 2025.3 to the release-blocking Plugin Verifier matrix.

## 0.6.1 — Early Access

- Uses a lean, structured terminal prompt shared by Claude and Codex.
- Added reliable immediate-child scope handling for Git repositories and other multi-directory commands.
- Added shell, platform, direction, action, and operand coverage to the terminal quality gates.
- Added a provider-neutral 50-case live quality gate and a 200-case deterministic terminal corpus.
- Kept installed-IDE automation opt-in so the headless release gate does not take over the desktop.

## 0.6.0 — Early Access

- Added explicit `# request` + Tab shell-command generation in IntelliJ's classic and Reworked terminals.
- Intercepted Tab only in the focused terminal, preserved ordinary shell completion, and inserted generated commands for review without executing them.
- Preserved typed requests when settings, terminal input, or provider state changes before a response arrives.
- Added terminal-specific settings, activity status, diagnostics, privacy controls, and rejection of multiline, control-sequence, and explanatory output.
- Shared only bounded terminal request metadata; terminal history, output, and project file contents remain local.

## 0.5.7 — Early Access

- Rejected provider explanations and no-op descriptions instead of inserting them into structured files such as YAML.
- Added explicit empty-response prompting when the cursor needs no insertion.
- Added a release-blocking safety matrix covering every exposed Claude model and every GPT model/reasoning combination.
- Preserved legitimate natural-language YAML scalar completions.

## 0.5.6 — Early Access

- Added native Claude Code and Codex subscription-backed inline completion.
- Added provider, model, and Codex reasoning selection.
- Added bounded language-aware current-file, recent-edit, open-tab, and opt-in cross-file context.
- Added single-line, multiline, and comment-directed completion support.
- Added local suggestion-tail reuse, request pacing, cancellation, syntax-aware validation, and diagnostics.
- Added read-only related cross-file edit proposals.
- Added deterministic, live-provider, lifecycle, and installed-IDE evaluation harnesses.
