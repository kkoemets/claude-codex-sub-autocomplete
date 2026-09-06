# API stability policy

Releases must contain **zero deprecated API usages**, including APIs scheduled for
removal. Binary compatibility by itself is insufficient. The Plugin Verifier gate
fails on every `VerifyPluginTask.FailureLevel` category except experimental API
usages. `publishPlugin` depends on the full `autocompleteReleaseGate`, so the
Gradle publication path cannot skip this policy by running publication alone.
Browser uploads require the same passing gate for the exact uploaded artifact.

Experimental integrations are retained by explicit project policy. They are
reported separately and are not a promise of compatibility with future IDEs.
Do not suppress verifier findings, add ignored-problem patterns, or use reflection
to conceal deprecated dependencies.

## Why 0.6.4 passed with warnings

The release configuration inherited the Gradle plugin's default failure levels:
compatibility problems, internal API usages, and override-only API usages. Those
defaults did not fail on deprecated or experimental APIs. All eight archived
verifier reports detected four deprecated usages and 41 experimental usages, but
returned a compatible verdict. Treating that verdict as sufficient was a release
policy error, not a failure to run the verifier.

The four deprecated usages all called `JediTermWidget.getTerminalStarter()` in
`ClassicTerminalCompletionTarget`. The unreleased fix uses the public
`TerminalPanel.getTerminalOutputStream()` interface instead. It preserves the
same terminal input queue and output-instance identity checks, including native
Tab forwarding and rejection after a terminal session changes. It does not write
directly to a process connector or queue a callback for a later terminal session.

The installed-IDE smoke-test diagnostics also use the non-deprecated output
interface, so the test harness no longer depends on the deprecated getter.

A source audit also found a reflective working-directory adapter whose new API
could return null and trigger the old getter on newer IDEA builds. The adapter
now chooses by API availability, rather than by the value returned: if the flow
getter exists, null, blank, and failed reads never fall back. Regression tests
count old-getter invocations to enforce that behavior, which bytecode verification
cannot inspect. All eight SDK targets were separately inspected: the old getter
is not deprecated on the shapes without the flow API, including the maintained
PyCharm build. This avoids assuming every product changes APIs at the same build
number.

The already published 0.6.4 artifact and its archived reports remain unchanged.
Any replacement must have a new version and pass the stricter gate.

## Retained experimental integrations

The current verifier counts 41 usage sites across these integrations. This is a
count of references and calls, not 41 distinct APIs.

| Integration | Purpose | Maintenance risk |
| --- | --- | --- |
| Reworked terminal: `TerminalView`, output models, shell integration, startup options, and `TerminalAllowedActionsProvider` | Read only focused editable shell input, reject active programs/alternate screens, collect bounded context, and insert commands for explicit user review | JetBrains marks the terminal integration API experimental. Signatures or behavior can change in a future platform release. |
| Classic terminal: `TerminalWidget.isCommandRunning()` and `getCurrentDirectory()` | Probe process ownership and working directory off the UI thread | These two widget methods are experimental even though the deprecated input-queue getter has a non-deprecated replacement. |
| Manual editor completion: `InlineCompletionEvent.ManualCall` | Route the plugin's shortcut to its own provider when other inline providers are installed | The provider-specific manual event is experimental. Switching to a generic event would change routing behavior. |

There are 38 terminal usage sites and three manual-editor usage sites in the
0.6.4 reports. Retaining these features accepts a bounded maintenance risk; it
does not require a plugin update for every IDE patch. A breaking platform change
can still require an update.

## Compatibility checks

- Compile against the oldest supported SDK, so newer-only methods are not
  accidentally introduced.
- Verify the packaged ZIP against the minimum and current supported products and
  JetBrains-recommended forward builds. Deprecated API findings block release
  even when every binary compatibility check succeeds.
- Run changed terminal/editor integration fixtures on an isolated display in
  IDEA, PyCharm, and Android Studio. Include session changes, pending responses,
  native Tab behavior, and runtime error logs.
- Review experimental findings with each release. Additional integration areas
  need a documented purpose and risk assessment; the exception is not permission
  to use internal, deprecated, or scheduled-for-removal APIs.
- An open upper build range permits installation. It cannot guarantee future
  compatibility, even when the current verifier report has no deprecations.

## Verification of the unreleased correction

Checked on 2026-09-06:

- Full headless release gate passed, including all 269 unit tests and the existing
  deterministic editor, terminal, provider, metadata, structure, and bundle gates.
- All eight verifier targets report **zero deprecated API usages** and the same
  **41 experimental usages**. No additional verifier failure categories remain.
- As a negative control, the unchanged published 0.6.4 signed ZIP was checked
  through the stricter task against PyCharm build 253.28294.336. Its verdict still
  said binary-compatible, but the task failed with `DEPRECATED_API_USAGES` for
  the four old calls.
- A dry run of `publishPlugin` includes `verifyPlugin` and
  `autocompleteReleaseGate` before publication.
- All 300 compiled production classes match the tested candidate ZIP. Its
  SHA-256 is `828dfd5861c82e93a1b5462884e421af814c6f9fb6618105c24a17061ce5b0ba`.

The installed-IDE harness now waits explicitly for the project frame before
interacting with it, and allows five minutes for startup to install the status
widget. Cold isolated launches exceeded the previous 15-second frame lookup and
one-minute widget readiness checks while the IDE was still loading. These bounded
startup waits do not change completion response deadlines or terminal behavior
assertions.

Final isolated terminal results (private Linux display, no host keyboard focus):

| Target | Result |
| --- | --- |
| PyCharm `PY-262.9437.214` | Passed Classic and Reworked terminal fixtures. |
| Android Studio `AI-261.26222.65.2614.16204760` | Passed Classic and Reworked terminal fixtures after the startup-wait correction. |
| IDEA `IU-262.10315.125` | Failed shell readiness before command insertion was exercised. Earlier attempts also encountered startup freezes and a status-widget timeout. |

The passing products verified physical Tab insertion without execution, explicit
Enter execution, native Tab delivery to running/alternate-screen programs, and
Reworked Find-field focus handling. All seven installed test homes matched the
candidate's four payload files. Final log scans recorded zero plugin-attributed
errors; unrelated IDE error records were 45 in IDEA, six in PyCharm, and 31 in
Android Studio. Those counts include repeated headers and are not counts of
distinct failures. Initial failures and retries are preserved separately.

**A new release is not cleared.** IDEA's isolated shell-readiness failure remains
unresolved. A passing API gate and the two passing products do not replace that
missing runtime result. The correction remains unreleased.

Local reports, the negative control, and artifact identity are retained under
`out/api-stability-evidence/` and the adjacent `out/api-stability-*.log` files.
The candidate is an unreleased source correction; it does not replace the
published 0.6.4 ZIP.

## References

- [Plugin Verifier failure levels](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin-failureLevel)
- [JetBrains terminal view API](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/TerminalView.kt)
- [JediTerm widget API](https://github.com/JetBrains/jediterm/blob/master/ui/src/com/jediterm/terminal/ui/JediTermWidget.java)
