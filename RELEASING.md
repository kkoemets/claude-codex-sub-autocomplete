# Releasing

Releases are built, verified, signed, and published locally.

## Prerequisites

- a clean `main` working tree;
- JDK 21;
- authenticated Claude Code and Codex subscriptions for live smoke tests;
- a JetBrains Marketplace account with permission to update the plugin and completed developer/vendor declarations (trader or non-trader, as applicable);
- a Marketplace permanent token only when publishing through Gradle; and
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

1. Update `pluginVersion`, `currentPlatformVersion`, `CHANGELOG.md`, and the `<change-notes>` section in `plugin.xml` together. Keep `<idea-plugin require-restart="true">`: plugin changes must use the IDE restart path. `currentPlatformVersion` must name the current stable IntelliJ release; keep `platformVersion`, `minimumPlatformVersion`, and `pluginSinceBuild` aligned with the oldest supported release.
2. Confirm the working tree contains no internal notes, credentials, generated reports, or unrelated changes.
   Refresh `currentPyCharmVersion` and `currentAndroidStudioVersion` alongside the
   current IDEA target. Android Studio's download version and underlying platform
   build differ; use the official Android Studio release list to select the target.
3. Run the full headless contributor-safe gate:

   ```bash
   ./gradlew clean autocompleteReleaseGate --no-daemon
   ```

   Deprecated and scheduled-for-removal APIs are release blockers, even when the
   verifier says the plugin is binary-compatible. Every verifier failure category
   except experimental API usages is fatal. Retained experimental terminal and
   manual-completion integrations are documented in
   [the API stability policy](docs/api-stability.md). Review their findings rather
   than interpreting a passing gate as a future-compatibility guarantee.

4. Sign the candidate and verify its signature before installed testing:

   ```bash
   ./gradlew signPlugin verifyPluginSignature --no-daemon
   ```

   Run installed-plugin fixtures against that exact signed release ZIP. On a shared host,
   use the isolated Linux display so the tests cannot take host keyboard focus:

   ```bash
   ./scripts/test-isolated-ides.sh /absolute/path/to/plugin.zip
   ```

   On an ARM host, run IDEA and PyCharm natively in the Linux guest. Android's
   Linux distribution requires x86-64; emulated UI freezes still fail the gate:

   ```bash
   IDE_TEST_PLATFORM=linux/arm64 ./scripts/test-isolated-ides.sh /absolute/path/to/plugin.zip
   IDE_TEST_PLATFORM=linux/amd64 ./scripts/test-isolated-ides.sh /absolute/path/to/plugin.zip autocompleteInstalledAndroidStudioTest
   ```

   For native macOS ARM input coverage, provision a disposable Tart VM with a
   logged-in desktop user, guest-local input/screen-capture permissions, and Python
   3.9+. Keep it stopped before invoking this runner. Supply a dedicated test-only
   Gradle cache under `out/`, a JDK 21 home, and the recorded immutable image digest:

   ```bash
   python3 scripts/test-isolated-macos.py \
     --tart /absolute/path/to/tart --vm autocomplete-test \
     --image-identity sha256:IMAGE_MANIFEST_SHA256 \
     --gradle-cache "$PWD/out/macos-gradle-cache" \
     --java-home /absolute/path/to/jdk/Contents/Home \
     /absolute/path/to/plugin.zip autocompleteInstalledAndroidStudioTest
   ```

   This uses a private 1920×1080 guest display with host graphics, audio, and
   clipboard sharing disabled. It archives independently verified guest inputs,
   output, environment, and completion receipts under `out/isolated-macos-tests/`.
   Never open its desktop on the shared host or grant host input permissions.

   Compatibility changes require the installed fixture checks in every maintained
   product, including editor dismissal/acceptance and terminal insertion without
   execution, direct physical Tab acceptance of the first automatic suggestion,
   and an update queued through the IDE installer followed by an actual process
   restart. Saved provider settings and physical terminal completion must work
   after restart, with no generated command executed. The platform must reject
   restart-free loading/unloading of the candidate descriptor.
   The scheduler fixture reinstalls the same candidate version; it does not prove
   the Install from Disk dialog flow or migration from the previous published ZIP.
   The isolated command uses physical keyboard input inside the private display.
   It preserves its source snapshot,
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

   IDEA and PyCharm fixtures first complete stock IDE evaluation setup without this
   plugin, then start a fresh plugin installation with that guest's generated trial
   state. Setup logs are retained separately; installed-plugin sessions must
   still satisfy the strict error policy below.

   Each installed-product run must also pass the runtime log gate after both IDE
   sessions shut down (before and after the installer update/restart). It checks every ERROR/SEVERE/FATAL record and saved
   error stacktraces, including threading assertions that do not fail functional
   checks, explicit diagnostic errors, and JVM crashes. Missing or empty logs fail
   verification. Plugin errors and unknown platform errors block release. The only
   accepted platform error is the independently reproduced Android Studio
   `AI-261.26222.65.2614.16204760` Reworked-terminal/C++ caret-listener lock exception
   on macOS with its bundled JDK 25.0.3. The gate requires the exact complete stack,
   associated metadata, and saved error record; accepted records remain reported.
   Any build, stack, runtime, or attribution change requires a new investigation.
   A green behavior check alone does not establish a clean runtime.

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
6. Collect the successful isolated run directories under one evidence directory.
   Keep reports, screenshots, incident notes, and submission records under the
   ignored `out/` directory or outside the checkout. Do not commit release evidence
   documents; public documentation should describe supported behavior and usage.
   Create `release-evidence.json`, mapping every maintained product to its relative
   run directory (one run may serve multiple products):

   ```json
   {"schemaVersion":1,"runs":{"IU":"run-arm","PY":"run-arm","AI":"run-amd64"}}
   ```

   Run the headless evidence validator before either website or Gradle publication:

   ```bash
   ./gradlew verifyReleaseIdeEvidence -PreleaseEvidenceDir=/absolute/path/to/evidence --no-daemon
   ```

   It checks the actual publication ZIP, current production and test sources,
   exact maintained IDE builds, full passing fixtures, installed payloads, and
   rescans archived logs. Missing, partial, or stale evidence fails. This task
   never launches an IDE. Re-signing changes archive identity and requires new
   exact-archive runtime evidence.

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

To submit the already verified ZIP through the authenticated Marketplace website,
open the existing plugin's administration page and choose **Upload Update**. Select
the exact signed ZIP, choose **Stable**, and leave **Make Hidden** unchecked for
public availability after approval. Browser uploads do not require `PUBLISH_TOKEN`.

Before submission, review the listing's description, change notes, Getting Started
instructions, policy links, media, and product compatibility. With **Use changes
from UI** off, the description and change notes come from `plugin.xml`; Getting
Started, custom pages, and policy links need separate updates in the listing.
Keep the published guide aligned with `docs/marketplace/guide.md` and Getting
Started with `docs/marketplace/getting-started.html`. Keep the advertised
behavior aligned with the version available to users. Keep submission IDs and
moderation records with the local release reports.

For Gradle publication to the public default channel, use:

```bash
./gradlew publishPlugin -PreleaseEvidenceDir=/absolute/path/to/evidence --no-daemon
```

The configured publishing task uses `PUBLISH_TOKEN`, channel `default`, and
`hidden=false`, and requires the full headless release gate, signature verification,
and archived isolated runtime evidence. Reuse the verified
artifact; a newly built or signed ZIP needs its own identity checks before submission.

An accepted upload is submitted for Marketplace review; it is not yet a public
release. Record the update ID and check its version, plugin ID, compatibility,
notes, and verifier results. JetBrains advises following up if no review-status
notification arrives within two business days; this is not an approval deadline.
See [Plugin updates](https://plugins.jetbrains.com/docs/marketplace/plugin-updates.html).

After approval, verify public availability and download the Marketplace artifact.
Marketplace may re-sign it, so its whole-ZIP checksum can differ from the author
ZIP. Verify archive/signature validity and compare plugin payloads with the
GitHub release before announcing publication.

## Rollback

Do not replace an existing version artifact. If a release is defective, hide or remove it in Marketplace administration, document the issue, increment the patch version, rerun every gate, and publish a corrected release.
