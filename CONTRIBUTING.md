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
- for interactive plugin checks: IntelliJ IDEA or PyCharm 2025.3 or newer, or Android Studio Quail 4 (2026.1.4); see the [compatibility record](docs/compatibility.md) for maintained test targets
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

Run installed-plugin fixture tests in a private Linux display to avoid taking
focus from your working desktop:

```bash
./scripts/test-isolated-ides.sh /absolute/path/to/plugin.zip
```

This requires Docker and Python 3. It runs the three maintained IDEs in an x86-64
Linux container with Xvfb and Openbox at 1920×1080. Keyboard events and focus stay
inside that display. The first run downloads several gigabytes of SDKs; Gradle
downloads are cached under `out/isolated-ide-tests/gradle-cache`. Each run preserves
its source snapshot, artifact checksum, container configuration, logs, and reports
under `out/isolated-ide-tests/run-*`. The original checkout, host display, and host
provider credentials are not mounted. The container is removed when the command
exits. To select one product, append `autocompleteInstalledIdeTest`,
`autocompleteInstalledPyCharmTest`, or `autocompleteInstalledAndroidStudioTest`.

These checks establish Linux integration, including guest keyboard input. Native
macOS or Windows behavior requires a separate graphical test machine or VM for
that operating system. Separate host windows, monitors, or macOS Spaces do not
isolate keyboard focus.

Native installed-IDE tests are opt-in because they take focus and control temporary
IDE windows. Use a dedicated test desktop. This task targets IntelliJ IDEA:

```bash
./gradlew autocompleteInstalledIdeTest --no-daemon
```

Fixture runs start without a plugin settings file and assert that automatic subscription
completion is enabled. They then configure the fixture provider without changing that
choice. The first editor case must produce this plugin's expected ghost text from typing
alone, with a recorded automatic provider request, before any completion hotkey is used.
With `-PideTestExternalTerminalInput=true`, that first editor case also waits for external
physical typing at its `typing-ready` screenshot; use the exact `type` value in the log.
The remaining editor cases use the input mode selected below.

Installed tests use the normal `buildPlugin` artifact by default. To install an
exact existing ZIP, including a signed release candidate, add
`-PideTestPluginPath=/absolute/path/to/plugin.zip`. This overrides the installed
artifact selected by `path.to.build.plugin`; each run logs its path, size, and
SHA-256 so runtime evidence can be tied to that ZIP.

That test uses a fixture provider. For real subscription-backed suggestions in
the packaged plugin, run the separate opt-in live test:

```bash
./gradlew autocompleteInstalledLiveIdeTest -PrequirePhysicalTyping=true --no-daemon
```

Use `autocompleteInstalledPyCharmTest` or `autocompleteInstalledAndroidStudioTest`
for the other maintained products. `autocompleteCrossIdeTest` runs all three
fixture checks sequentially. Each target checks its relevant languages, dismissal
and acceptance of ghost text, and physical Tab in a real terminal. The terminal
fixture returns a sentinel command; the test fails if it executes without Enter.
The test IDEs use isolated settings. The classic shell fixture skips user startup
files; the Reworked terminal uses the IDE's default shell.
These are foreground desktop tests: keep the test IDE focused while they run.
On macOS, grant the launched IDE Accessibility access for physical keyboard input.
Alternatively, add `-PideTestExternalTerminalInput=true` to supply the terminal
keystrokes manually or with an authorized desktop-control tool. At each engine's
empty prompt, type `# autocomplete terminal compatibility check`, wait for the
`terminal-requested` screenshot, and press Tab. Assertions still verify the real
terminal output and the absence of execution; each input step allows two minutes.
After insertion, the harness clears and restores the command through the terminal
input API to prove it is editable, then sends an explicit Enter as a positive
control for a unique absolute sentinel. Both engines' running-program and
alternate-program stages, plus Reworked's Find-field stage, require one additional
physical Tab each; do not type a request at those stages. The program probes assert
exactly one Tab byte and no provider call.
At the Find stage, press Tab in the focused search field, observe the native UI
transition, then create the exact `.search-tab-ack` path printed by the harness
(for example, with `touch`). Keep the test IDE foreground until its assertions
finish. The acknowledgment prevents unrelated application focus loss from passing
the search test without a physical key press.
Starter timeout scales with repetitions (17, 24, or 31 minutes), plus two minutes
per editor case when external editor input is enabled.
For the terminal regression suite in all three products, use
`./gradlew autocompleteCrossIdeTest -PideTestTerminalsOnly=true -PideTestExternalTerminalInput=true --no-daemon`.

The corresponding live tasks are `autocompleteInstalledLivePyCharmTest` and
`autocompleteInstalledLiveAndroidStudioTest`. Live checks measure actual provider
completions separately from the deterministic installed-plugin checks.

Maintain `currentPlatformVersion`, `currentPyCharmVersion`, and
`currentAndroidStudioVersion` in `gradle.properties`. The verifier explicitly
includes baseline/current IDEA and PyCharm plus the maintained Android Studio
release, alongside recommended IDEA builds. Keep the compile SDK at the oldest
supported platform; do not lower the build floor just to bypass an install error.

Live checks run the selected providers in separate temporary IDE instances, using
language examples relevant to each product. Each suggestion must stay out of the document until
accepted, and acceptance must preserve the surrounding text. Both CLIs must be
installed and signed in. `-PideTestLiveProviders=codex` selects one provider for
diagnosis; it does not establish coverage for both. Explicit alternative Codex
profiles can be selected with `-PideTestCodexModel=... -PideTestCodexEffort=...`.
By default, the test uses IDE document and action APIs to check rendering and
acceptance. `-PrequirePhysicalTyping=true` uses the IDE Robot for typing and Tab
acceptance. When that Robot lacks keyboard permission, add
`-PideTestExternalEditorInput=true` to supply editor typing through the keyboard
or authorized desktop control; this option implies physical typing and preserves
API-based acceptance.

For each `typing-ready` screenshot and `External editor input ready` log entry,
activate the test IDE and type exactly the JSON-quoted `type` value at the prepared
caret, including any trailing space. Do not type Enter or Tab. The harness waits
up to two minutes for the complete document to equal its expected contents, then
checks suggestion rendering, dismissal in fixture runs, and acceptance through
the IDE action API. Wait for the next ready entry before typing again. Logs record
the input and acceptance paths separately; external editor typing does not establish
physical Tab acceptance coverage.

After every installed run closes the IDE, the runtime log gate scans its logs and
saved error stacktraces. Plugin-attributed ERROR/SEVERE/FATAL entries fail the test,
including errors logged by platform code with this plugin in the stack. Unrelated
IDE diagnostics are reported separately. This gate also runs for terminal-only
checks and preserves any earlier assertion failure. A missing/empty `idea.log`
cannot pass. Headless unit tests exercise the log parser and the service's
background process probes, EDT insertion, stale-response rejection, and Tab ordering.

Without an active Claude subscription, run:

```bash
./gradlew claudeCliCompatibilitySmoke autocompleteReleaseGate --no-daemon
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
