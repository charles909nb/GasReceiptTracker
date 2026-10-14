# Finance Packet Archive and Vehicle MPG (2.2 test iteration)

## Finance
- When the driver confirms an email was sent, a permanent complete PDF is copied to private `files/exports/Finance_Sent_<request-id>.pdf`. The archived PDF cannot be regenerated or edited from a later active PR.
- Requests > **Find complete packets sent to Finance** searches by purchase date, dollar amount, vehicle, local PR reference, request title, and sent date. Default sort: newest purchase date; alternatives oldest date, highest amount, lowest amount.
- Each result opens the complete PDF in the in-app preview, offers a new email (without changing the historical sent status), and exports the exact archived PDF using Android's document picker.
- The archived PDF is included in the existing ZIP backup, and restored file references are compatible with the app's existing export URI rules.
- A saved packet is only labeled sent after explicit driver confirmation. This is not Finance's acknowledgment or payment approval. Unavailable files are displayed as missing rather than silently recreated.

## Vehicle MPG
- Requests > **Vehicle MPG trends and maintenance flags** displays a per-vehicle offline chart.
- Uses consecutive fueling odometer differences divided by gallons purchased at the *later* fueling. Invalid/duplicate odometers and implausible intervals are excluded.
- Compares each interval with the previous three valid intervals. A drop greater than 20%, and at least 2 MPG, is flagged for possible inspection.
- This estimate assumes comparable full refills. Partial fills, missed fuel purchases, towing, idling, weather and different routes can distort readings. Flagged points are screening signals, not diagnoses.
- Historical receipts are preserved, including purchase date, amount and odometer.

## Version discipline
This is Version 2.2 TEST, not a new stable major version. Original Karuk PR form, signed Excel, packet page order, receipt OCR and backup behavior are preserved.
