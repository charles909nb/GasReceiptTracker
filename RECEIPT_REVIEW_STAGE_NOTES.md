# Gas Receipt Tracker - Visual Receipt Review (2.1 test build)

Purpose: connect OCR field values to exact source areas on the user's original receipt image.

- New `ReceiptReviewActivity` opens automatically following camera/document-scanner/gallery OCR. The receipt appears full-page with translucent highlights. The selected field is red; remaining located fields are yellow. Tap a highlighted value or use Previous/Next to cycle station, date, time, fuel type, gallons, unit price, total, payment brand, and printed last four digits.
- A number not located by OCR is shown as **Not located**, with the full photo available for manual correction; the app must never invent highlight positions.
- ML Kit original and contrast-enhanced scans now retain normalized line/word bounding boxes. The original image stays unmodified. Evidence, raw OCR (potential complete PAN redacted), edits, last-four comparison, and timestamps are persisted with each saved receipt in SQLite.
- The preferred card brand and last four digits are stored in the profile. The receipt's separate card brand and printed last four digits are not overwritten by profile data. A difference triggers a prominent red mismatch and requires explicit user acknowledgment before the fuel receipt/PR can be saved. For a confirmed different card, the original official Gas purchase requisition uses the actual card for that transaction, not the profile default.
- The original receipt's scanned word locations and verification status can be reopened read-only under Fuel and Requests / Search saved fuel receipts. Historical cards preserve their comparison profile at time of review.
- Original Karuk Gas XLS, sheet signature placement, issue-date logic, trip usage PDF, queued travel/work-order PRs, email confirmation and backup/restore behavior remain unchanged.
- The OCR result is a suggestion. The user is responsible for verifying printed details. Full on-phone camera testing remains necessary even when Android emulator checks pass.
- Scope remains a 2.1 test iteration, not a new stable major version.

Automated tests: see `ReceiptVerificationTest.kt` for highlight-to-text evidence mapping, field cycling, saved review, privacy redaction, and mismatch acknowledgment, plus original form and payment brand parser regressions.
