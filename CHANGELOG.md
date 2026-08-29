# Changelog

All notable user-facing changes are documented here.

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
