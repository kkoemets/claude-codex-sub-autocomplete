# Releasing

Releases are built, verified, signed, and published locally.

## Prerequisites

- a clean `main` working tree;
- JDK 21;
- authenticated Claude Code and Codex subscriptions for live smoke tests;
- an accepted JetBrains Marketplace Developer Agreement and verified trader profile;
- a Marketplace permanent token; and
- a signing private key and certificate chain stored outside the repository.

Never commit signing material or a Marketplace token. Provide credentials only through these environment variables:

```text
CERTIFICATE_CHAIN
CERTIFICATE_CHAIN_FILE
PRIVATE_KEY
PRIVATE_KEY_PASSWORD
PUBLISH_TOKEN
```

The Gradle plugin accepts the signing PEM values directly or as single-line Base64-encoded values.
`CERTIFICATE_CHAIN_FILE` must point to the public certificate-chain PEM used to verify the signed ZIP.

## Create signing material

Follow JetBrains' [plugin signing instructions](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html). Generate and store the private key outside the repository, protect it with a strong password, and back it up in an access-controlled credential store. Rotate the certificate before expiry and immediately after suspected disclosure.

## Prepare the release

1. Update `pluginVersion`, `currentPlatformVersion`, `CHANGELOG.md`, and the `<change-notes>` section in `plugin.xml` together. `currentPlatformVersion` must name the current stable IntelliJ release; keep `platformVersion`, `minimumPlatformVersion`, and `pluginSinceBuild` aligned with the oldest supported release.
2. Confirm the working tree contains no internal notes, credentials, generated reports, or unrelated changes.
   Refresh `currentPyCharmVersion` and `currentAndroidStudioVersion` alongside the
   current IDEA target. Android Studio's download version and underlying platform
   build differ; use the official Android Studio release list to select the target.
3. Run the full headless contributor-safe gate:

   ```bash
   ./gradlew clean autocompleteReleaseGate --no-daemon
   ```

4. Run installed-plugin fixtures against the exact release ZIP. On a shared host,
   use the isolated Linux display so the tests cannot take host keyboard focus:

   ```bash
   ./scripts/test-isolated-ides.sh /absolute/path/to/plugin.zip
   ```

   Compatibility changes require the installed fixture checks in every maintained
   product, including editor dismissal/acceptance and terminal insertion without
   execution. The isolated command runs all three. It preserves its source snapshot,
   artifact identity, environment, and reports under `out/isolated-ide-tests/`.

   Linux results do not establish macOS or Windows native input behavior. For those
   operating systems, use a separate graphical test machine or VM. The native task
   below opens windows and takes focus on the machine where it runs:

   ```bash
   ./gradlew autocompleteCrossIdeTest --no-daemon
   ```

   Inspect each product's settings, status widget, and suggestion rendering at the
   target viewport, including loading and completed states. Record the exact IDE
   builds and distinguish fixture checks from live provider coverage.

   Each installed-product run must also pass the runtime log gate after IDE
   shutdown. It rejects plugin-attributed ERROR/SEVERE/FATAL records and saved
   error stacktraces, including threading assertions that do not fail functional
   checks. Missing logs fail verification. Report unrelated IDE errors separately;
   a green JUnit behavior check alone does not establish a clean plugin runtime.

   Preserve the tested ZIP, JUnit XML, runtime logs, and selected screenshots
   outside `build/` before a clean rebuild. Record the installed artifact hash.

   This uses a fixture provider and verifies IDE integration only. Verify real
   subscription suggestions separately with both authenticated providers:

   ```bash
   ./gradlew autocompleteInstalledLiveIdeTest -PrequirePhysicalTyping=true --no-daemon
   ```

5. Run the canonical live terminal quality gate:

   ```bash
   ./gradlew terminalLiveEval --no-daemon
   ```

   This runs the same 50 provider-neutral cases once against the plugin defaults:
   Claude Haiku and Codex `gpt-5.6-luna` with `low` reasoning. The harness
   resolves these profiles from `ProviderPolicy`, so release checks track the
   defaults users receive. Each provider must pass at least 45/50
   cases (90%); the release target is 46/50 or better (92%). The critical
   direct-child Git-repository case must pass independently, every category must
   have a passing case, and every non-empty generated command must receive a local
   shell syntax check. Inspect the redacted reports in
   `build/reports/terminal-live-evals/` before continuing.

   Missing authentication, unsupported models, and exhausted subscription limits
   are incomplete live validation. Do not count an alternative model run as a
   passing result for the default model.

   When no Claude subscription is available, validate its integration with
   `./gradlew claudeCliCompatibilitySmoke autocompleteReleaseGate --no-daemon`
   and the isolated fixture command above.
   Record Claude model quality as untested because no subscription was available.
   This checks the real CLI contract, subprocess failure handling, and packaged
   IDE integration with fixtures; it does not produce a passing Claude live-quality
   score. Record physical keyboard coverage separately from IDE action/API coverage.

   The 200-case deterministic corpus remains part of the headless release gate.
   It checks the shared prompt, sanitizer, semantic scorer, and safety contracts
   without calling either provider.
6. Export the signing variables and build the signed artifact:

   ```bash
   ./gradlew signPlugin verifyPluginSignature --no-daemon
   ```

7. Inspect the signed ZIP contents and calculate its checksum:

   ```bash
   unzip -l build/distributions/*-signed.zip
   shasum -a 256 build/distributions/*-signed.zip
   ```

   Compare every signed ZIP payload file with the unsigned ZIP and the plugin
   files installed in each passing runtime fixture. If any payload differs,
   rerun the affected runtime checks before distributing the signed build.

## Publish

Create and push an annotated version tag only after the signed artifact passes every gate. Attach the exact signed ZIP and a `SHA256SUMS` file to the corresponding GitHub Release.

Publish to JetBrains Marketplace's public default channel with:

```bash
./gradlew publishPlugin --no-daemon
```

The configured publishing task uses `PUBLISH_TOKEN`, channel `default`, and `hidden=false`. Confirm the uploaded Marketplace version, plugin ID, compatibility range, release notes, and checksum match the GitHub Release before announcing it.

## Rollback

Do not replace an existing version artifact. If a release is defective, hide or remove it in Marketplace administration, document the issue, increment the patch version, rerun every gate, and publish a corrected release.
