# Gas Receipt Tracker - Receipt Accuracy and History Phase

## Requirements
- Date on Karuk Gas purchase requisition is today's local device date when generated, not the printed receipt date.
- Preserve the original receipt date and source photograph with the fuel purchase.
- Recognize receipt layout with 8.765G, PRICE/GAL $6.9993 and FUEL TOTAL $61.35.
- Original Gas XLS and its signature field must remain unchanged in layout.
- Keep all trips, receipt scans, corrected values, original OCR, request history and generated documents retrievable on the phone.
- Never commit the customer's original receipt image or identifying transaction numbers to public GitHub.

## Changes on receipt-date-history-v2
- Dates separated at generation time in OfficialPurchaseRequisition.
- ReceiptTextParser handles G gallon suffix, 3/4-place per-gallon price and amount labels; scan retries contrast-enhanced OCR if missing fields.
- Fuel screen saves raw OCR, printed receipt date, original image, fuel type, card suffix and user-corrected values.
- SQLite database indexes requests, trips, receipts, exported documents, settings and audit events.
- Search saved fuel receipts from the Requests tab.
- Backup format 3 contains database snapshot; older backups can be reindexed.
- Automated regression tests for OCR patterns, database history, purchase requisition dates and previous request workflow.

## Not yet user-verified
- Actual physical camera recognition of the supplied receipt; use phone test after Android emulator tests pass.
- Editable history with before/after audit and duplicate fuel purchase detection.
- Monthly fuel, mileage and expenditure reports.
- A persistent production signing key for seamless APK updates.
