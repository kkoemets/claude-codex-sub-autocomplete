# IDE compatibility

Version **0.6.4** uses one ZIP for IntelliJ IDEA and PyCharm 2025.3+ and Android Studio Quail 4 (2026.1.4). It requires the bundled Terminal plugin and Java 21 or newer. The minimum IntelliJ Platform build is 253, with no artificial upper cap; future IDE releases still require verification.

A fresh installation enables automatic completion. Existing saved automatic, hotkey-only, and disabled preferences are preserved. Provider CLIs must be installed and authenticated before they can generate suggestions.

## Release artifact

Download the signed ZIP and checksum file from [release 0.6.4](https://github.com/kkoemets/claude-codex-sub-autocomplete/releases/tag/v0.6.4).

| Item | Value |
| --- | --- |
| ZIP | `claude-codex-sub-autocomplete-0.6.4-signed.zip` |
| Size | 958,961 bytes |
| SHA-256 | `10f9cc27df5b82935a187a34931510c4d15d1b645afa75ad46b5b31cb4354d3f` |
| Main plugin JAR SHA-256 | `5ddfa25c228967c0eb01d99f8b19ad7547addad5cd89da54444376b0db9f64a1` |
| Verification date | 2026-09-06 |

The completed build passed signing and signature verification. Independent artifact review confirmed ZIP and nested-JAR integrity, identical signed/unsigned payloads, all 300 production classes matching compiled output, and complete installed plugin inventories and bytes matching this signed ZIP in all three passing products. Package scans found no bundled test fixtures, runtime log scanner, credential filenames, or private-key markers.

Only the artifact identified above is covered by this record. Earlier local 0.6.4 candidates were superseded and must not be distributed as this release.

## Installed-product checks

All three installed-product tests passed using the exact signed ZIP identified above. Each run retained finalized JUnit results, installed plugin files, runtime logs, screenshots, and container/display provenance.

The current matrix runs Linux/amd64 IDEs in a container with Xvfb at 1920×1080 and Openbox. IDE Robot sends input events within that guest display; the host display is not part of this matrix.

| Product | Runtime build | Editor coverage | Classic / Reworked | Plugin errors / unrelated records |
| --- | --- | --- | --- | --- |
| IntelliJ IDEA 2026.2.2 | 262.10315.125 | 13 cases; passed | Both passed | 0 / 10 |
| PyCharm 2026.2.1 | 262.9437.214 | 4 cases; passed | Both passed | 0 / 6 |
| Android Studio Quail 4, 2026.1.4.7 | 261.26222.65.2614.16204760 | 4 cases; passed | Both passed | 0 / 11 |

Each IDE began without a plugin settings file and reported `enabled=true`, `manualOnly=false`, and `automaticEngine=SELECTED_SUBSCRIPTION`. Typing produced the exact fixture suggestion and a new automatic-request diagnostic from this plugin before the editor completion shortcut was used. The first cases were TypeScript in IntelliJ IDEA, Python in PyCharm, and Java in Android Studio.

For the first editor case, IDE Robot typing requests an automatic suggestion before the editor completion shortcut. The harness then dismisses that suggestion, manually requests a replacement, and accepts the replacement with an IDE Robot Tab event. This sequence does not establish Tab acceptance of the initial automatic suggestion. The remaining editor cases retain their separately recorded input and acceptance methods.

In both terminal modes, each product replaced a request with an editable command, left the execution sentinel absent until deliberate Enter, and then created that sentinel. Ordinary and alternate-screen child programs each received exactly one Tab byte without a provider request. Reworked terminal Find handled Tab as search navigation while leaving the pending terminal request unchanged.

The post-shutdown log scans found no plugin-attributed errors. Unrelated records were retained: IntelliJ profiler configuration serialization, a PyCharm platform freeze, and Android Studio Assistant startup and platform event-dispatch errors. Counts include separate context records and saved stacktraces, so they are not counts of distinct incidents. The environment runs under CPU emulation; these tests do not establish native performance.

## Headless checks

| Check | Result for this artifact |
| --- | --- |
| Unit tests | 267 passed; no failures, errors, or skips |
| Deterministic editor cases | 99/99 passed |
| Deterministic terminal cases | 200/200 passed |
| Provider/model checks | 429/429 passed across 39 profiles |
| Metadata, structure, compatibility policy, bundle size | Passed |

| Product | Verified builds |
| --- | --- |
| IntelliJ IDEA | 253.28294.334, 253.33813.55, 261.27258.48, 262.10315.125, 263.3889.65 |
| PyCharm | 253.28294.336, 262.9437.214 |
| Android Studio | 261.26222.65.2614.16204760 |

All eight Plugin Verifier targets were compatible. Each reported 4 deprecated and 41 experimental API usages; these remain maintenance risks as platform APIs evolve.

## Coverage limits

- Installed-product checks use a fixture provider. They verify IDE integration and do not measure authenticated model quality. Earlier live-provider results belong to earlier artifacts and are not fresh live-model evidence for this ZIP.
- The first editor case uses guest IDE Robot typing and Tab acceptance of a manually retriggered replacement; other editor cases use IDE document/action APIs. Terminal checks use guest IDE Robot Tab events. Robot events within a virtual display do not establish native host keyboard or macOS foreground behavior.
- Earlier full macOS checks used a superseded artifact. Current-candidate macOS attempts did not complete a full matrix and are not substituted for the Linux results. Windows runtime behavior has not been exercised.
- Visual review covered sampled editor request/suggestion, terminal request/review, and settings frames at 1920×1080. Pane bounds remained stable in the sampled pairs. The default settings dialog clips some right-side text and fields; inline toolbars can overlap preceding code or IDE banners. Android Studio’s open Assistant pane narrows the editor enough to clip the XML continuation horizontally. These are documented viewport limitations. Active loading/spinner frames, continuous layout stability, and measured contrast were not established.
- Claude CLI flag and signed-out error checks do not establish authenticated Claude quality. Compatibility checks do not guarantee generated-command correctness or future IDE support.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for reproducible fixture and live-provider checks and [RELEASING.md](../RELEASING.md) for verification and signing commands.
