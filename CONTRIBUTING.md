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
- IntelliJ IDEA 2026.1 or newer
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

## Pull requests

- Keep each pull request focused on one coherent change.
- Add or update tests for behavior changes.
- Preserve bounded context, secret redaction, explicit provider selection, cancellation, and rejection of incomplete output.
- Update the README, privacy policy, EULA, or change notes when user-visible behavior or data flow changes.
- Include the commands used to verify the change.

Commits should use an imperative Conventional Commit-style subject, such as `fix: Preserve whitespace before inline suggestions`.

## License

By contributing, you agree that your contribution is licensed under the repository's [MIT License](LICENSE). No contributor license agreement is required.
