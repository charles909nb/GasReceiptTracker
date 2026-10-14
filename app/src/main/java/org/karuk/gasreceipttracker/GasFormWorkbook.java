package org.karuk.gasreceipttracker;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;

/** Operates on the supplied original Gas XLS, never on a rebuilt look-alike form. */
public final class GasFormWorkbook {
    private GasFormWorkbook() {}
    public static final String ASSET = "forms/Karuk_Gas_Original.xls";
    public static final String TEMPLATE_SHA256 = "058c43238947d1feeff139a1b03e0aa4bfa78265e4f9aea1c1777c17ea0eefb5";
    private static HSSFCell cell(HSSFSheet s, String address) {
        CellReference p = new CellReference(address);
        HSSFRow r = s.getRow(p.getRow()); if (r == null) r = s.createRow(p.getRow());
        HSSFCell c = r.getCell(p.getCol()); return c == null ? r.createCell(p.getCol()) : c;
    }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static double number(String value, String label, boolean optional) {
        String s = clean(value).replace("$", "").replace(",", "");
        if (s.isEmpty() && optional) return Double.NaN;
        try {
            double d = new BigDecimal(s).doubleValue();
            if (!Double.isFinite(d) || d <= 0) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a positive " + label); }
    }
    private static void numberFormat(HSSFWorkbook w, HSSFCell c, String format) {
        // Preserve original borders/font/alignment; equivalent numeric cells share this format.
        // Avoid POI style creation, which initializes desktop java.awt.Color on Android.
        c.getCellStyle().setDataFormat(w.createDataFormat().getFormat(format));
    }
    public static String displayDate(String date) {
        String d=clean(date);
        for(String pattern: new String[]{"yyyy-MM-dd", "M/d/uuuu", "M/d/uu", "M-d-uuuu", "M-d-uu", "MMM d, uuuu h:mm a", "MMMM d, uuuu", "MMM d, uuuu"}) {
            try { return LocalDate.parse(d, DateTimeFormatter.ofPattern(pattern, Locale.US)).format(DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)); }
            catch(DateTimeException ignored) {}
        }
        return d; // An unrecognized receipt date is preserved, not invented.
    }
    public static byte[] fill(InputStream original, String date, String driver, String card,
            String vehicle, String gallons, String unitPrice, String total,
            byte[] signaturePng, int signatureWidth, int signatureHeight) throws IOException {
        if(clean(driver).isEmpty()) throw new IllegalArgumentException("Enter the requester name in your profile");
        if(clean(vehicle).isEmpty()) throw new IllegalArgumentException("Choose or enter a vehicle number");
        if(clean(date).isEmpty()) throw new IllegalArgumentException("Enter the purchase date");
        card=clean(card); if(!card.isEmpty() && !card.matches("[0-9]{4}")) throw new IllegalArgumentException("Use only the credit card's last four digits");
        double qty=number(gallons,"gallon quantity",false), price=number(unitPrice,"unit price",true);
        double cost=new BigDecimal(Double.toString(number(total,"total amount",false))).setScale(2,RoundingMode.HALF_UP).doubleValue();
        try(HSSFWorkbook w=new HSSFWorkbook(original)) {
            if(w.getNumberOfSheets()!=1 || !w.getSheetName(0).equals("Gas")) throw new IOException("Bundled original Gas worksheet is invalid");
            HSSFSheet s=w.getSheetAt(0);
            if(!cell(s,"I47").getStringCellValue().contains("Signature of Individual Requesting")) throw new IOException("Original signature label is missing");
            cell(s,"C7").setCellValue(displayDate(date));
            cell(s,"C13").setCellValue(clean(driver));
            cell(s,"C14").setCellValue(card.isEmpty()?"":"pay CC "+card);
            cell(s,"B19").setCellValue(qty); numberFormat(w,cell(s,"B19"),"0.000");
            cell(s,"D19").setCellValue("Gas car "+clean(vehicle));
            if(Double.isFinite(price)){cell(s,"K19").setCellValue(price);numberFormat(w,cell(s,"K19"),"0.000");}
            cell(s,"M19").setCellValue(cost); numberFormat(w,cell(s,"M19"),"0.00");
            // The original SUM formula remains the source of the accounting amount.
            if(cell(s,"I40").getCellType()!=CellType.FORMULA)throw new IOException("Accounting SUM formula is missing");
            numberFormat(w,cell(s,"I40"),"0.00");
            if(signaturePng!=null && signaturePng.length>0) {
                if(signatureWidth<=0||signatureHeight<=0)throw new IOException("Signature image cannot be decoded");
                HSSFPatriarch drawing=s.getDrawingPatriarch(); if(drawing==null)drawing=s.createDrawingPatriarch();
                // Signature is wholly ABOVE I47; I46:O46 carries the line above its caption.
                float availableWidth=0; for(int c=8;c<15;c++)availableWidth+=columnPoints(s,c);
                float availableHeight=0;for(int r=43;r<46;r++)availableHeight+=rowPoints(s,r);
                float factor=Math.min((availableWidth-10)/signatureWidth,(availableHeight-6)/signatureHeight);
                float left=columnOffset(s,8)+(availableWidth-signatureWidth*factor)/2;
                float top=rowOffset(s,43)+availableHeight-signatureHeight*factor-3;
                HSSFClientAnchor a=anchor(s,left,top,left+signatureWidth*factor,top+signatureHeight*factor);
                a.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                drawing.createPicture(a,w.addPicture(signaturePng,Workbook.PICTURE_TYPE_PNG));
            }
            w.getCreationHelper().createFormulaEvaluator().evaluateAll();
            w.setActiveSheet(0);s.setSelected(true);w.setFirstVisibleTab(0);
            w.setPrintArea(0,0,15,0,47);s.setFitToPage(true);s.getPrintSetup().setFitWidth((short)1);s.getPrintSetup().setFitHeight((short)1);
            s.setPrintGridlines(false); s.setPrintRowAndColumnHeadings(false);
            ByteArrayOutputStream out=new ByteArrayOutputStream();w.write(out);return out.toByteArray();
        }
    }
    public static float columnPoints(HSSFSheet s,int c){return s.isColumnHidden(c)?0:s.getColumnWidth(c)/256f*7f*.75f;}
    public static float rowPoints(HSSFSheet s,int r){HSSFRow row=s.getRow(r);return row==null?s.getDefaultRowHeightInPoints():(row.getZeroHeight()?0:row.getHeightInPoints());}
    public static float columnOffset(HSSFSheet s,int c){float x=0;for(int i=0;i<c;i++)x+=columnPoints(s,i);return x;}
    public static float rowOffset(HSSFSheet s,int r){float y=0;for(int i=0;i<r;i++)y+=rowPoints(s,i);return y;}
    private static int[] columnAt(HSSFSheet s,float x){int c=0;while(c<254&&x>=columnPoints(s,c)){float v=columnPoints(s,c++);x-=v;}return new int[]{c,Math.round(1023*x/Math.max(1,columnPoints(s,c)))};}
    private static int[] rowAt(HSSFSheet s,float y){int r=0;while(r<65534&&y>=rowPoints(s,r)){float v=rowPoints(s,r++);y-=v;}return new int[]{r,Math.round(255*y/Math.max(1,rowPoints(s,r)))};}
    private static HSSFClientAnchor anchor(HSSFSheet s,float x1,float y1,float x2,float y2){int[] a=columnAt(s,x1),b=rowAt(s,y1),c=columnAt(s,x2),d=rowAt(s,y2);return new HSSFClientAnchor(a[1],b[1],c[1],d[1],(short)a[0],b[0],(short)c[0],d[0]);}
}
