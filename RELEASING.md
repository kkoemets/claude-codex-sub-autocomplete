# Releasing

Releases are built, verified, signed, and published locally. This repository intentionally does not use GitHub Actions.

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
PRIVATE_KEY
PRIVATE_KEY_PASSWORD
PUBLISH_TOKEN
```

The Gradle plugin accepts PEM values directly or as single-line Base64-encoded values.

## Create signing material

Follow JetBrains' [plugin signing instructions](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html). Generate and store the private key outside the repository, protect it with a strong password, and back it up in an access-controlled credential store. Rotate the certificate before expiry and immediately after suspected disclosure.

## Prepare the release

1. Update `pluginVersion`, `CHANGELOG.md`, and the `<change-notes>` section in `plugin.xml` together.
2. Confirm the working tree contains no internal notes, credentials, generated reports, or unrelated changes.
3. Run the full contributor-safe gate:

   ```bash
   ./gradlew clean autocompleteReleaseGate --no-daemon
   ```

4. Run live Claude and Codex smoke evaluations and inspect the redacted reports.
5. Export the signing variables and build the signed artifact:

   ```bash
   ./gradlew signPlugin verifyPluginSignature --no-daemon
   ```

6. Inspect the signed ZIP contents and calculate its checksum:

   ```bash
   unzip -l build/distributions/*-signed.zip
   shasum -a 256 build/distributions/*-signed.zip
   ```

## Publish

Create and push an annotated version tag only after the signed artifact passes every gate. Attach the exact signed ZIP and a `SHA256SUMS` file to the corresponding GitHub Release.

Publish to JetBrains Marketplace's public default channel with:

```bash
./gradlew publishPlugin --no-daemon
```

The configured publishing task uses `PUBLISH_TOKEN`, channel `default`, and `hidden=false`. Confirm the uploaded Marketplace version, plugin ID, compatibility range, release notes, and checksum match the GitHub Release before announcing it.

## Rollback

Do not replace an existing version artifact. If a release is defective, hide or remove it in Marketplace administration, document the issue, increment the patch version, rerun every gate, and publish a corrected release.
