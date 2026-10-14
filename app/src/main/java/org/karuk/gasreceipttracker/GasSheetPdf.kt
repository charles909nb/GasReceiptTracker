package org.karuk.gasreceipttracker

import android.graphics.*
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import org.apache.poi.hssf.usermodel.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Renders the actual filled original worksheet, including its styles and picture anchors. */
object GasSheetPdf {
    fun write(workbook: ByteArray, destination: File) {
        val doc = PdfDocument()
        try {
            append(doc, workbook, 1)
            FileOutputStream(destination).use { doc.writeTo(it); it.fd.sync() }
        } finally { doc.close() }
    }
    fun append(doc: PdfDocument, workbook: ByteArray, number: Int) {
        HSSFWorkbook(ByteArrayInputStream(workbook)).use { book ->
            val sheet = book.getSheet("Gas") ?: error("Gas sheet missing")
            val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, number).create())
            val canvas = page.canvas
            val width = GasFormWorkbook.columnOffset(sheet, 16)
            val height = GasFormWorkbook.rowOffset(sheet, 48)
            val scale = minOf(540f / width, 720f / height)
            canvas.save()
            canvas.translate((612f - width * scale) / 2, (792f - height * scale) / 2)
            canvas.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val evaluator = book.creationHelper.createFormulaEvaluator()
            val formatter = DataFormatter(Locale.US)
            val merges = sheet.mergedRegions
            fun color(index: Short): Int {
                // Use native BIFF RGB values, avoiding desktop-only java.awt.Color on Android.
                val rgb = book.workbook.customPalette.getColor(index.toInt()) ?: return Color.BLACK
                return Color.rgb(rgb[0].toInt() and 255, rgb[1].toInt() and 255, rgb[2].toInt() and 255)
            }
            fun box(r: Int, c: Int, merge: CellRangeAddress? = null): RectF = RectF(
                GasFormWorkbook.columnOffset(sheet, c), GasFormWorkbook.rowOffset(sheet, r),
                GasFormWorkbook.columnOffset(sheet, (merge?.lastColumn ?: c) + 1),
                GasFormWorkbook.rowOffset(sheet, (merge?.lastRow ?: r) + 1))
            for (r in 0..47) for (c in 0..15) {
                val cell = sheet.getRow(r)?.getCell(c) ?: continue
                val style = cell.cellStyle
                val b = box(r, c)
                if (style.fillPattern == FillPatternType.SOLID_FOREGROUND) {
                    paint.style = Paint.Style.FILL; paint.color = color(style.fillForegroundColor)
                    canvas.drawRect(b, paint)
                }
            }
            fun border(style: BorderStyle, index: Short, x1: Float, y1: Float, x2: Float, y2: Float) {
                if (style == BorderStyle.NONE) return
                paint.color = color(index); paint.style = Paint.Style.STROKE
                paint.strokeWidth = when(style) { BorderStyle.MEDIUM -> 1.4f; BorderStyle.THICK -> 2f; else -> .65f }
                paint.pathEffect = when(style) {
                    BorderStyle.DASHED -> DashPathEffect(floatArrayOf(4f,3f),0f)
                    BorderStyle.DOTTED -> DashPathEffect(floatArrayOf(1f,2f),0f)
                    else -> null
                }
                canvas.drawLine(x1,y1,x2,y2,paint)
                paint.pathEffect = null
            }
            // Excel does not display the stored borders inside a merged range.
            // Keep the original perimeter styles without exposing those hidden internal lines.
            for (r in 0..47) for (c in 0..15) {
                val cell=sheet.getRow(r)?.getCell(c) ?: continue
                val st=cell.cellStyle; val b=box(r,c)
                val merge=merges.firstOrNull { it.isInRange(r,c) }
                if(merge==null || c==merge.firstColumn)
                    border(st.borderLeft,st.leftBorderColor,b.left,b.top,b.left,b.bottom)
                if(merge==null || c==merge.lastColumn)
                    border(st.borderRight,st.rightBorderColor,b.right,b.top,b.right,b.bottom)
                if(merge==null || r==merge.firstRow)
                    border(st.borderTop,st.topBorderColor,b.left,b.top,b.right,b.top)
                if(merge==null || r==merge.lastRow)
                    border(st.borderBottom,st.bottomBorderColor,b.left,b.bottom,b.right,b.bottom)
            }
            for (r in 0..47) for (c in 0..15) {
                val cell = sheet.getRow(r)?.getCell(c) ?: continue
                val merge = merges.firstOrNull { it.isInRange(r,c) }
                if (merge != null && (merge.firstRow != r || merge.firstColumn != c)) continue
                val value=formatter.formatCellValue(cell,evaluator)
                if(value.isBlank()) continue
                val st=cell.cellStyle; val font=book.getFontAt(st.fontIndex.toInt())
                paint.style=Paint.Style.FILL;paint.color=color(font.color)
                paint.typeface=Typeface.create(font.fontName,when {
                    font.bold && font.italic -> Typeface.BOLD_ITALIC
                    font.bold -> Typeface.BOLD; font.italic -> Typeface.ITALIC; else -> Typeface.NORMAL
                })
                paint.textSize=font.fontHeightInPoints.toFloat()
                val b=box(r,c,merge); val usable=b.width()-5f
                val printable=value.replace(Regex("[ ]{6,}"),"\n")
                fun lines(): List<String> {
                    val result=mutableListOf<String>()
                    for(paragraph in printable.split('\n')) {
                        if(!st.wrapText){ result.add(paragraph);continue }
                        var line=""
                        for(word in paragraph.split(Regex("\\s+"))) {
                            val candidate=if(line.isBlank()) word else "$line $word"
                            if(line.isNotBlank() && paint.measureText(candidate)>usable){ result.add(line);line=word }
                            else line=candidate
                        }
                        result.add(line)
                    }
                    return result
                }
                var textLines=lines()
                while(paint.textSize>5f && (textLines.any{paint.measureText(it)>usable} || textLines.size*paint.fontSpacing>b.height()-3f)) {
                    paint.textSize-=.25f; textLines=lines()
                }
                val contentHeight=textLines.size*paint.fontSpacing
                val top=when(st.verticalAlignment){
                    VerticalAlignment.TOP -> b.top+2
                    VerticalAlignment.CENTER -> b.top+(b.height()-contentHeight)/2
                    else -> b.bottom-contentHeight-2
                }
                val numeric=cell.cellType==CellType.NUMERIC || cell.cellType==CellType.FORMULA
                canvas.save();canvas.clipRect(b)
                for((index,line) in textLines.withIndex()) {
                    val x=when(st.alignment){
                        HorizontalAlignment.CENTER,HorizontalAlignment.CENTER_SELECTION -> b.centerX()-paint.measureText(line)/2
                        HorizontalAlignment.RIGHT -> b.right-paint.measureText(line)-2
                        HorizontalAlignment.GENERAL -> if(numeric)b.right-paint.measureText(line)-2 else b.left+2
                        else -> b.left+2
                    }
                    canvas.drawText(line,x,top-paint.fontMetrics.ascent+index*paint.fontSpacing,paint)
                }
                canvas.restore()
            }
            for(shape in sheet.drawingPatriarch?.children.orEmpty()) {
                if(shape !is HSSFPicture) continue
                val a=shape.clientAnchor
                val image=BitmapFactory.decodeByteArray(shape.pictureData.data,0,shape.pictureData.data.size)
                    ?: error("Signature picture cannot be read from Excel")
                val target=RectF(
                    GasFormWorkbook.columnOffset(sheet,a.col1.toInt())+GasFormWorkbook.columnPoints(sheet,a.col1.toInt())*a.dx1/1023f,
                    GasFormWorkbook.rowOffset(sheet,a.row1)+GasFormWorkbook.rowPoints(sheet,a.row1)*a.dy1/255f,
                    GasFormWorkbook.columnOffset(sheet,a.col2.toInt())+GasFormWorkbook.columnPoints(sheet,a.col2.toInt())*a.dx2/1023f,
                    GasFormWorkbook.rowOffset(sheet,a.row2)+GasFormWorkbook.rowPoints(sheet,a.row2)*a.dy2/255f)
                paint.style=Paint.Style.FILL;paint.isFilterBitmap=true
                canvas.drawBitmap(image,null,target,paint);image.recycle()
            }
            canvas.restore();doc.finishPage(page)
        }
    }
}
