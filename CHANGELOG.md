# Changelog

All notable user-facing changes are documented here.

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
