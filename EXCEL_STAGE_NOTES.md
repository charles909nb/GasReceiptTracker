# Excel integration - 1.8-excel-preview

Source form: user-supplied Purchase_Requisitions V2.xls, Gas worksheet.
The original XLS was opened using Apache POI, with other sheets removed. Cell styles,
merged ranges, row heights, column widths, static wording and accounting SUM were retained.
Example personal/transaction entries and unused shared strings were cleared. The obsolete
sample date text in B7 was reduced to Date:. The original supplied file is not modified.

Bundled asset: app/src/main/assets/forms/Karuk_Gas_Original.xls
SHA-256: 058c43238947d1feeff139a1b03e0aa4bfa78265e4f9aea1c1777c17ea0eefb5

Verified mapping (1-based Excel coordinates): C7 date; C13 requester; C14 last four card
reference; B19 gallons; D19 vehicle description; K19 optional unit price; M19 actual cost;
I40 retains SUM(M19:O36). Signature is aspect-fitted within I44:O46, above caption I47.
Only the Gas worksheet is exported. Generated PDFs are rendered FROM the completed XLS,
not from a retyped form or a manually imported PDF. XLS/PDF are cached in app-private exports.

Sheets now offers PDF review, Excel save, packet review, and a separate email action.
PdfPreviewActivity is internal/offline and supports next/previous page and zoom.
Document sharing uses a FileProvider restricted to the exports directory.

Checks: original-template shape/style retention; JVM fill/formula/signature assertions;
Android instrumentation for clean-install asset, signed workbook, changed inputs/validation,
three-page packet, and in-app preview. Sample files say TEST ONLY and are not real purchases.

Scope: no changes to receipt OCR, trip lifecycle, reimbursement email sending, global signature
storage, or backup design. Successful compilation does not establish all remaining project
features or production readiness. Signing of preview updates still requires final hardening.
