## What changed

<!-- Describe the focused change and its user or developer impact. -->

## Safety and privacy

- [ ] Provider tools, file writes, command execution, and authentication behavior are unchanged or explicitly reviewed.
- [ ] Context remains bounded and sensitive values remain redacted.
- [ ] No prompts, source code, credentials, private paths, or generated reports are included.

## Verification

<!-- List the exact commands and manual scenarios used. -->

- [ ] Relevant unit tests pass.
- [ ] `./gradlew autocompleteDeterministicEval` passes 99/99 cases.
- [ ] User-facing documentation and change notes are updated when required.
