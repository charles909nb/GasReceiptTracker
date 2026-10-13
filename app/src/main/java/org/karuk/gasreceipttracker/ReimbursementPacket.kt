package org.karuk.gasreceipttracker

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Builds a single PDF: official requisition background, receipt, then five-trip sheets. */
object ReimbursementPacket {
    fun importOfficialPdf(context: Context, uri: Uri) {
        val target = File(context.filesDir, "Karuk_Purchase_Requisition_Official.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read the official PDF" }
            target.outputStream().use { input.copyTo(it) }
        }
        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { require(it.pageCount > 0) { "Official PDF is empty" } }
        }
    }
    fun hasTemplate(context: Context): Boolean =
        File(context.filesDir, "Karuk_Purchase_Requisition_Official.pdf").exists()

    fun create(context: Context, trips: JSONArray, receipts: JSONArray,
               driver: String, card: String, signaturePath: String): Uri {
        require(hasTemplate(context)) { "Import the official Purchase Requisition PDF first" }
        require(receipts.length() > 0) { "Save a fuel receipt first" }
        val receipt = receipts.getJSONObject(receipts.length() - 1)
        val sig = File(signaturePath)
        require(signaturePath.isNotBlank() && sig.isFile) { "Sign the Purchase Requisition first" }
        val doc = PdfDocument()
        var pageNumber = 0
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        try {
            val template = File(context.filesDir, "Karuk_Purchase_Requisition_Official.pdf")
            ParcelFileDescriptor.open(template, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { original ->
                            val width = 612
                            val height = (original.height.toFloat() * width / original.width).toInt()
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            original.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNumber).create())
                            val canvas = page.canvas
                            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
                            bitmap.recycle()
                            // Coordinates are specific to the imported Karuk gas requisition print layout.
                            // Require review of the generated PDF before sending.
                            if (i == 0) {
                                paint.textSize = 9f
                                canvas.drawText(driver.take(40), 120f, 165f, paint)
                                canvas.drawText("pay CC " + card.takeLast(4), 120f, 182f, paint)
                                canvas.drawText(receipt.optString("gallons").take(14), 72f, 302f, paint)
                                canvas.drawText("Gas car " + receipt.optString("vehicle").take(20), 172f, 302f, paint)
                                canvas.drawText(receipt.optString("cost").take(14), 505f, 302f, paint)
                                val signature = BitmapFactory.decodeFile(sig.absolutePath)
                                if (signature != null) {
                                    canvas.drawBitmap(signature, null, RectF(380f, 505f, 525f, 543f), paint)
                                    signature.recycle()
                                }
                            }
                            doc.finishPage(page)
                        }
                    }
                }
            }
            for (i in 0 until receipts.length()) {
                val r = receipts.getJSONObject(i)
                val imageUri = Uri.parse(r.getString("image"))
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (imageUri.scheme == "file") {
                    BitmapFactory.decodeFile(imageUri.path, options)
                } else {
                    context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it, null, options) }
                }
                var sample = 1
                while (options.outWidth / sample > 1800 || options.outHeight / sample > 2400) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val image = (if (imageUri.scheme == "file") {
                    BitmapFactory.decodeFile(imageUri.path, options)
                } else {
                    context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it, null, options) }
                }) ?: throw IllegalStateException("Receipt image " + (i + 1) + " is unavailable")
                val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, ++pageNumber).create())
                val canvas = page.canvas
                paint.textSize = 12f
                canvas.drawText("FUEL RECEIPT - " + r.optString("date").take(45), 35f, 36f, paint)
                val scale = minOf(540f / image.width, 705f / image.height)
                canvas.drawBitmap(image, null, RectF(35f, 54f, 35f + image.width * scale, 54f + image.height * scale), paint)
                image.recycle()
                doc.finishPage(page)
            }
            VehicleUsagePdf.append(doc, trips, receipts, pageNumber + 1)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "Karuk_Reimbursement_" + System.currentTimeMillis() + ".pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, "Download/GasReceiptTracker")
            }
            val output = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create reimbursement PDF")
            context.contentResolver.openOutputStream(output).use { stream ->
                requireNotNull(stream) { "Cannot write reimbursement PDF" }
                doc.writeTo(stream)
            }
            return output
        } finally { doc.close() }
    }
}
