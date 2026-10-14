# Gas Receipt Tracker 1.9 - interface and request flow

Baseline: tested 1.8 commit ec1a738c4361c7b74f5fb9f35282a7fe68249b78.
Scope: native red-accent interface, automatic signature routing, request queue, and safe post-email continuation.

## Implemented
- Persistent Trips, Fuel, Docs, Requests and More navigation; labeled inputs; request status cards; approved icon unchanged.
- New travel or work order gets a local PR reference and separate trips, receipt, drafts and signature.
- Existing 1.8 active records migrate without clearing them.
- Missing signature redirects to Sign and returns to the requested PDF/Excel/email action after saving.
- Each request stores its own signature; a new request starts unsigned.
- Editable email preview with To, CC, self-CC, subject and body; extra template fields for PR reference and title.
- Returning from an email app asks whether the message was sent. ACTION_SEND provides no delivery confirmation. Only explicit Sent confirmation archives and advances; Not sent preserves the request.
- Next queued request is selected automatically. If no queued request remains, create one blank PR using profile defaults.
- Closed requests keep their receipt, signature, trip records, PDF reference and user-confirmed email metadata.
- Backup format 2 includes the request queue, profiles/CC, per-request signatures, receipt images and existing exported documents. Legacy format 1 remains readable.
- Four original worksheet/PDF tests retained; twelve request/navigation/signature/backup tests added.

## Preserved
Original XLS bytes and GasFormWorkbook, GasSheetPdf, OfficialPurchaseRequisition, ReimbursementPacket and VehicleUsagePdf unchanged.
No new official P.O. number, Finance authorization, actual email delivery assertion, or new travel-authorization form is created. PR references are internal tracking IDs. Work order and Travel are queue categories for the existing vehicle/fuel workflow.

## Still separate work
Receipt recognition accuracy on real photographs; secure consistent production signing and upgrade delivery; broader history editing/reporting.
This is a test candidate, not user-approved stable.
