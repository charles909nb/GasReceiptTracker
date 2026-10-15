# Gas Receipt Tracker 2.4.3 - Backfilled Trips and Fuel Receipt Links
Date: October 15, 2026
Status: In Progress / Ready for Device Verification; Not Confirmed Complete

## User-reported problem
After scanning and saving fuel first, opening Trips and trying to enter the starting odometer presented "Finish the fueled request, or use New travel / PR" and refused the trip. The user sometimes enters earlier travel after fueling and needs to keep receipts attached to their actual mileage entries. The cleaned-up Fuel page should not be redesigned; Documents & Review changes are deferred.

## Approved changes limited to Trips and receipt association
1. Permit starting a trip after a fuel receipt is saved, within the same open request. Preserve the protection against changing requests during a pending Finance email.
2. For backfilled trips show one compact Trip date field with a same-request receipt-date suggestion; store real entered travel date separate from when the entry was recorded. Ordinary trip-first workflow remains unchanged.
3. Assign stable trip IDs. Auto-link an unlinked receipt only if exactly one **completed** trip has the same vehicle, its fuel odometer within trip start/end readings, and its purchase date within travel dates. An open/unbounded or ambiguous trip will NOT produce a guessed link.
4. Allow manual attachment or change from the trip list, and unlink without deleting a receipt. A manual decision cannot be silently overridden by auto-matching. Move a receipt association rather than duplicating transactions.
5. Persist `tripId` inside the original receipt record. Use the existing request workflow and database index; no changes to the official Excel form, Finance packet or Documents & Review.

## Verification requirements
- A fuel-first trip can be started and ended in the same request without a blocking message.
- Auto-link only when vehicle/date/odometer range give one match, and not until the trip is completed.
- Out-of-range, mismatched vehicle/date, ambiguous trips stay unlinked and allow manual attachment.
- Manual reassignment and unlink do not duplicate, delete or change transaction amounts.
- Associations remain after request sync, SQLite backup/restore and app update.
- Signing certificate/package identity match the previously installed permanent release; higher versionCode.

Status remains open until Neal tests and confirms the release on the phone.