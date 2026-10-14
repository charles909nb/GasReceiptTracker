from pathlib import Path

root = Path('app/src/main/java/org/karuk/gasreceipttracker')

def replace_exact(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError('Expected exactly one source location: ' + old[:90])
    return text.replace(old, new, 1)

p = root / 'GasSheetPdf.kt'
s = p.read_text()
s = replace_exact(s, 'import android.graphics.*\n', 'import android.graphics.*\nimport android.graphics.Color\n')
s = replace_exact(s, '        PdfDocument().use { doc ->\n', '        val doc = PdfDocument()\n        try {\n')
s = replace_exact(s, '            FileOutputStream(destination).use { doc.writeTo(it); it.fd.sync() }\n        }\n', '            FileOutputStream(destination).use { doc.writeTo(it); it.fd.sync() }\n        } finally { doc.close() }\n')
s = replace_exact(s, '                val rgb = book.customPalette.getColor(index)?.triplet ?: return Color.BLACK\n                return Color.rgb(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())', '                // Read native BIFF RGB bytes, not desktop-only HSSFColor/java.awt.Color.\n                val rgb = book.workbook.customPalette.getColor(index.toInt()) ?: return Color.BLACK\n                return Color.rgb(rgb[0].toInt() and 255, rgb[1].toInt() and 255, rgb[2].toInt() and 255)')
p.write_text(s)

p = root / 'ReimbursementPacket.kt'
s = p.read_text()
s = replace_exact(s, '            PdfDocument().use{doc ->\n', '            val doc = PdfDocument()\n            try {\n')
s = replace_exact(s, '                FileOutputStream(part).use{doc.writeTo(it);it.fd.sync()}\n            }\n', '                FileOutputStream(part).use{doc.writeTo(it);it.fd.sync()}\n            } finally { doc.close() }\n')
p.write_text(s)

p = root / 'GasFormWorkbook.java'
s = p.read_text()
s = replace_exact(s, '        HSSFCellStyle style = w.createCellStyle(); style.cloneStyleFrom(c.getCellStyle());\n        style.setDataFormat(w.createDataFormat().getFormat(format)); c.setCellStyle(style);', '        // Preserve original borders/font/alignment; equivalent numeric cells share this format.\n        // Avoid POI style creation, which initializes desktop java.awt.Color on Android.\n        c.getCellStyle().setDataFormat(w.createDataFormat().getFormat(format));')
p.write_text(s)
print('Applied Android PDF resource lifecycle and desktop-free original XLS formatting/palette handling.')
