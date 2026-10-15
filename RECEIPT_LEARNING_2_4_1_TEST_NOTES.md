# Receipt Tracker 2.4.1 - user test and correction record

Date: October 15, 2026
Status: READY FOR USER TEST - NOT CONFIRMED COMPLETE

## User-reported 2.4 defects
- The receipt editor was confusing: price per gallon appeared twice while working through the receipt.
- Touching a receipt highlight could switch the selected field without warning. A price correction could accidentally be entered under gallons.
- Tap-to-rescan immediately replaced OCR values, without an explicit accept/reject step.
- During APK update Android offered Google Play Protect scanning. User declined and uninstalled/reinstalled instead of an in-place update.

## Fixes built for 2.4.1
- Display scanned receipt details once as a read-only summary on the Fuel screen; use a single dedicated field editor.
- Replace the 10-field Previous/Next navigation with an explicit field dropdown.
- If a different highlighted field is touched, give a warning and never silently switch the editable field.
- Tap-rescan now presents a suggestion and requires explicit acceptance. It never automatically changes values.
- Keep card-mismatch controls contextual; preserve the original image and prior Finance packets.
- Never train receipt-layout hints from an unconfirmed original OCR box. Learn accepted, tapped locations.
- Increase versionCode 16 -> 18 while preserving application ID and private release signer.

## Automated verification
- GitHub Actions run 38820304374 succeeded with 69 Android tests.
- Tests include a regression for selecting price, tapping highlighted gallons, and saving the price without changing gallons.
- In-place debug update 18 -> 19 preserved app marker on emulator.
- Release signing verified against permanent certificate SHA-256 8058c63d913384ab6d340d1a88dd7058695d6746948a2e416cab1ceedf420254, same certificate as permanent Version 2.4.
- Official Karuk Gas worksheet remained unchanged (SHA-256 058c43238947d1feeff139a1b03e0aa4bfa78265e4f9aea1c1777c17ea0eefb5).

## Remaining checks - still open
- User must confirm real-phone in-place installation over the permanently signed 2.4.
- User must verify price/gallons correction and accepted suggestions are intuitive and accurate.
- If the phone displays a Play Protect request, allow its scan; if installation fails or it flags a harmful app, preserve installed data and report the exact message rather than uninstalling.
- Only mark Confirmed Complete after user explicitly confirms successful testing.

No unrelated Elders Program master/backlog items were changed.
