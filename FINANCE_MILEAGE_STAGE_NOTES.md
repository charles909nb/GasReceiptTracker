# Finance Packet Archive & Vehicle MPG - Version 2.2 test candidate

Scope: add new features to the last verified 2.1 Gas Receipt Tracker without changing the original Karuk Gas worksheet, signature placement, OCR overlays, reviewed PDF order, emailing, or automatic next-PR behavior.

## Finance packets
- Save the **exact PDF bytes** that are attached to the user's Finance email. An app-private immutable copy is created before opening the Android email chooser. It is never regenerated when viewed, exported, or resent.
- Only after the user explicitly confirms the email **was sent** does its packet appear in the searchable Finance archive. Android cannot verify delivery or Finance receipt.
- Default archive order: latest **purchase requisition issue date**, with the purchase/receipt date a separate search factor.
- Search by PR date, receipt purchase date, PR reference, amount, vehicle, station, recipient, Finance status; sort by either date, amount, vehicle, status or sent time.
- Stored metadata: transaction amount in integer cents, the two distinct dates, vehicle, station, recipient/CC, email subject, sent confirmation, PDF URI, checksum, byte count and Finance follow-up status/notes.
- Allow in-app PDF review, Save As / Download through Android document picker, and resend the original packet via the email chooser. Resending does not create another PR or claim an email was delivered.
- Follow-up statuses are user-entered: Awaiting Finance, Needs follow-up, Completed, Returned for correction, Cancelled. No false assertion of Finance completion.
- SQLite schema 1->2 migration adds indexed finance_packets table without deleting existing data. Older sent requests are indexed; if an older app did not record the actual PR issue date, it is labeled unknown rather than inferred from receipt or sent date. Old original PDF URI is used where it still exists.
- ZIP backup includes the private Finance PDF originals and Finance status/notes. Use the correct PDF copy when restoring.

## Vehicle MPG
- Add full / partial / unknown fill level to each fuel purchase.
- MPG is computed only from one driver-confirmed full-tank fueling to the next, using the difference in fueling odometers and the sum of gallons purchased since the previous full tank, **including partial fill-ups**. The first full tank starts a baseline; it is not independently an MPG sample.
- Unknown intermediate fill type breaks a cycle; implausible or reversed odometer readings are excluded.
- Dashboard selects a vehicle, shows a native MPG line graph, per-cycle math, and (after at least three previous complete valid cycles) flags a >=20% efficiency drop relative to their average.
- Flag is labeled a possible maintenance check, not a diagnosis. Tire pressure, driving conditions, idling, fill levels and odometer errors may also explain changes.
- Allow older fuel receipts to be classified as Full / Partial / Unknown, with timestamps and an activity log. Metadata corrections do not alter previously submitted PDF packets.
- Export chart data to CSV using Android's file picker.

## Preservation / guardrails
- Original official Karuk Excel gas form and template SHA are unchanged.
- PDF signature and email-confirmation flow are unchanged.
- Existing SQLite receipt history, OCR evidence, trip records, emailed packet metadata, and ZIP backup remain compatible.
- This is a development/test version; do not mark stable until the user confirms installed-device functionality.

## Verification
- Existing 38 Android tests retained. Added 15 Finance archive / mileage tests (53 total).
- New emulator screenshots: UI_Finance_Archive_Search.png and UI_Vehicle_MPG_Dashboard.png.
