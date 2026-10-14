# In-place Android updates: repeatable private signing

All stable updates must retain these Android properties:

- Application package ID: `org.karuk.gasreceipttracker.preview` (same as the existing preview's package, so its private data directory persists across updates).
- Version code increases with every build (2.2 used `13`; 2.3 uses `14`).
- **Every distributed update must be signed with the same private release keystore and alias `gasreceipttracker-release`.** Never use the default CI debug signing key for delivery.
- Pin the certificate SHA-256 fingerprint: `8058c63d913384ab6d340d1a88dd7058695d6746948a2e416cab1ceedf420254`. The signing script refuses output signed by any other certificate.
- GitHub CI tests the app and produces **an unsigned release APK**; no private signing key or password is committed to this public repository or present in build logs. The private signing key and password are retained outside this repository for future releases.
- The unsigned APK must be signed using `tools/sign-release-locally.sh` and validated with Android `apksigner verify`, including v2/v3 signing, before delivery.

## One-time migration boundary

An installed app signed by a previous unknown debug key cannot be upgraded in place to a new signing key. Android enforces this. Back up using the old installed app, uninstall the old preview once, install the first new permanently signed build, and restore the ZIP. Versions 2.4 onward can install over 2.3 without uninstalling, **provided they use this same keystore and package ID**.

The ZIP backup contains the receipt database, scanned images, signatures, and original sent Finance packet PDFs. Never uninstall before exporting and verifying the ZIP backup. The signing key is not a user data backup.

## Access and safeguards

The signing keystore and credentials are stored in a **private user Library folder**, not in GitHub source, GitHub Actions build artifacts, an APK or a public report. If lost, the signing identity cannot be reproduced and another migration would be required. Restrict access to the key and protect backups.