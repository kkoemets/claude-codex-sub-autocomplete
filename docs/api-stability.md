# API stability policy

Releases must contain **zero deprecated API usages**, including APIs scheduled for
removal. Binary compatibility by itself is insufficient. The Plugin Verifier gate
fails on every failure category except experimental API usage. Both automated
publication and browser uploads require the full release gate for the exact ZIP.

Do not suppress verifier findings, add ignored-problem patterns, or use reflection
to conceal deprecated dependencies. Cross-version adapters must select APIs by
availability; a missing value from a newer API must not trigger a deprecated
fallback.

## Experimental integrations

The following integrations are retained for their functionality. Experimental
APIs can change in future IDE releases and may require a plugin update.

| Integration | Purpose | Maintenance risk |
| --- | --- | --- |
| Reworked terminal views, output models, shell integration, startup options, and allowed actions | Inspect focused editable shell input, reject running programs and alternate screens, and insert commands for review | Experimental terminal signatures or behavior can change. |
| Classic terminal process and working-directory probes | Inspect shell state off the UI thread | The widget methods are experimental even though the input queue uses a non-deprecated interface. |
| Provider-specific manual inline-completion event | Route the shortcut to this plugin's provider when other inline providers are installed | The provider-specific event is experimental; generic events have different routing behavior. |

## Release requirements

- Compile against the oldest supported SDK.
- Verify the packaged ZIP against minimum and current supported IDEs and
  JetBrains-recommended forward builds.
- Run installed editor and terminal fixtures in each maintained product, including
  physical Tab input, pending responses, installer restart, and runtime error logs.
- Review experimental findings with each release. Additional integrations require
  a documented purpose and maintenance risk.

An open upper build range allows installation; it does not guarantee compatibility
with future IDE versions. See [RELEASING.md](../RELEASING.md) for the complete process.

## References

- [Plugin Verifier failure levels](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin-failureLevel)
- [API changes and compatibility](https://plugins.jetbrains.com/docs/intellij/api-changes-list.html)
- [Experimental APIs](https://plugins.jetbrains.com/docs/intellij/api-notable-list.html)
