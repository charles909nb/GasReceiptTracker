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
p.write_text(s)

p = root / 'ReimbursementPacket.kt'
s = p.read_text()
s = replace_exact(s, '            PdfDocument().use{doc ->\n', '            val doc = PdfDocument()\n            try {\n')
s = replace_exact(s, '                FileOutputStream(part).use{doc.writeTo(it);it.fd.sync()}\n            }\n', '                FileOutputStream(part).use{doc.writeTo(it);it.fd.sync()}\n            } finally { doc.close() }\n')
p.write_text(s)
print('Applied explicit Android PdfDocument close lifecycle and graphics Color binding.')
