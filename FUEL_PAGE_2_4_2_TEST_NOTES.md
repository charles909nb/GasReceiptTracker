# Gas Receipt Tracker 2.4.2 - Fuel page / automatic receipt draft saves

Date: October 15, 2026
Status: DEVELOPMENT - READY FOR USER TEST AFTER ANDROID VERIFICATION; NOT CONFIRMED COMPLETE
Based on: tested build of 2.4.1 with user-reported missing-odometer persistence defect.

## Authorized Fuel page changes
- Photograph stored in the current request's receipts JSON and SQLite search index immediately on capture, before OCR starts.
- OCR results and checked corrections continuously update the SAME saved draft record; no duplicate receipt or lost image when an odometer reading is absent.
- Vehicle and odometer first; vehicle dropdown auto-selects a sole vehicle, supports switching among stored vehicles, and Enter another vehicle.
- One visible Scan receipt action, automatic crop/document scan with image-gallery support; fallback photo/gallery dialog on scanner failure.
- Auto-open receipt review after scan. Keep manual review button for revisiting receipt checks.
- One Save fuel receipt action. On missing fields, preserve as incomplete draft and show exactly what's still required; when complete, mark the same ID complete and continue to documents.
- Remove Save fuel draft, camera-duplicate, separate OCR rescan button, and user-facing explanatory notes that repeated program specifications.
- Backups include draft ID, receipt image, OCR and incomplete status; restoring does not silently mark incomplete as complete.
- Minimal safety guard prevents incomplete receipt from generating/sending official finance forms.

## Test and confirmation requirements
- Android instrumentation for incomplete draft, complete-in-place update, card verification, backup/restore, and Fuel page layout and save behavior.
- Original Karuk Gas Excel sheet checksum must remain 058c43238947d1feeff139a1b03e0aa4bfa78265e4f9aea1c1777c17ea0eefb5.
- Verify next update remains package org.karuk.gasreceipttracker.preview, version code > 18, and original permanent signing certificate SHA-256 8058c63d913384ab6d340d1a88dd7058695d6746948a2e416cab1ceedf420254.
- Run device tests and sign release privately, not inside GitHub Actions.
- Await Neal's phone test before changing status to Confirmed Complete.

Unrelated Elders Program backlog, other app sections, and the official Excel asset remain unchanged.