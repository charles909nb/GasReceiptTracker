# Gas Receipt Tracker 1.9.1 - PR date, SQLite records, receipt parsing

Baseline: 1.9, commit 02d75bcd49ad1592f218b0bd9aa79a6f6319e784. Original Gas XLS and visual layout remain unchanged.

## Implemented in this candidate
1. New/generated PRs use the device's local current date. Receipt purchase date/time remains separate and unchanged in saved receipt data and email templates. A saved historical document is opened unchanged, not regenerated with a new date.
2. All existing app state (profile, templates, requests, drafts, trips, receipt fields, confirmation metadata) now writes to a transaction-protected SQLite database. Existing 1.9 preferences migrate once without deleting their safety copy. Indexed records support search by PR, vehicle, date, station or amount. Scan text, extracted values/warnings, document snapshots, signature/photo file links are retained. Backup format 3 includes database history and remains compatible with formats 1 and 2.
3. OCR combines text cells on the same image row before parsing amounts, recognizes 8.765G and PRICE/GAL $6.999, prioritizes the labelled fuel total, and retains both raw OCR and user-reviewed values. It never derives a missing total from gallons times price. Missing/contradictory fields are flagged. Failed fields replace stale earlier suggestions instead of reusing them.

## Verification
Retain all 16 original form/workflow tests. Add 10 date/database/parser tests, including real ML Kit recognition of the fuel-only portion of the supplied photo. The cropped test fixture excludes card, invoice, and authorization details, is only in the test APK, and is not bundled in the user app.

## Limits / remaining work
- SQLite is local to this app on this phone, not cross-device cloud sync. Backups are essential before uninstalling/changing phones.
- OCR remains a suggested transcription to review, not guaranteed extraction for every station/lighting condition.
- Production signing/updating and advanced history editing/reporting remain open. Continue same major version; do not promote stable without user's confirmation.
- Original form and signature placement, red interface, request queue, post-email confirmation and in-app PDF preview must not be removed.
