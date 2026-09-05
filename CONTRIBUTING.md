# Contributing

Thanks for helping improve Claude/Codex Sub Autocomplete.

## Before opening a change

- Search existing issues before filing a new one.
- Use a feature request to discuss behavior changes that affect privacy, provider invocation, completion pacing, or compatibility.
- Never include source code, prompts, credentials, authentication output, or unredacted diagnostics from a private project.
- Keep provider authentication subscription-backed. API-key fallbacks are outside the supported Claude and Codex paths.

## Development setup

Requirements:

- JDK 21
- IntelliJ IDEA 2025.3 or newer
- the repository's Gradle wrapper

Run the fast checks with:

```bash
./gradlew test autocompleteDeterministicEval buildPlugin
```

Run the complete headless pre-release suite with:

```bash
./gradlew clean autocompleteReleaseGate --no-daemon
```

If authenticated Claude Code and Codex subscriptions are available, run the
shared 50-case network-backed terminal gate separately:

```bash
./gradlew terminalLiveEval --no-daemon
```

It evaluates both providers against identical cases and shared scoring rules;
provider-specific cases or scoring exceptions are not accepted.

The installed-IDE test is deliberately opt-in because it launches and controls a temporary IntelliJ window:

```bash
./gradlew autocompleteInstalledIdeTest --no-daemon
```

That test uses a fixture provider. For real subscription-backed suggestions in
the packaged plugin, run the separate opt-in live test:

```bash
./gradlew autocompleteInstalledLiveIdeTest -PrequirePhysicalTyping=true --no-daemon
```

It checks Claude and Codex in separate temporary IDE instances, using TypeScript,
Python, and Java examples. Each suggestion must stay out of the document until
accepted, and acceptance must preserve the surrounding text. Both CLIs must be
installed and signed in. `-PideTestLiveProviders=codex` selects one provider for
diagnosis; it does not establish coverage for both. Explicit alternative Codex
profiles can be selected with `-PideTestCodexModel=... -PideTestCodexEffort=...`.
Without `-PrequirePhysicalTyping=true`, the test uses IDE document and action APIs
to check rendering and acceptance. That mode does not verify physical keyboard input.

Without an active Claude subscription, run:

```bash
./gradlew claudeCliCompatibilitySmoke autocompleteInteractiveReleaseGate --no-daemon
```

The CLI check verifies the real
installed command-line interface and, when signed out, its authentication errors.
The unit suite exercises the production Claude backend with subprocess fixtures,
including model selection, editor and terminal prompts, rejected authentication,
CLI errors, cancellation, timeouts, and output limits. The installed fixture test
checks ghost text and acceptance. These checks validate integration; they do not
measure authenticated Claude model quality.

## Pull requests

- Keep each pull request focused on one coherent change.
- Add or update tests for behavior changes.
- Preserve bounded context, secret redaction, explicit provider selection, cancellation, and rejection of incomplete output.
- Update the README, privacy policy, EULA, or change notes when user-visible behavior or data flow changes.
- Include the commands used to verify the change.

Commits should use an imperative Conventional Commit-style subject, such as `fix: Preserve whitespace before inline suggestions`.

## License

By contributing, you agree that your contribution is licensed under the repository's [MIT License](LICENSE). No contributor license agreement is required.
