package com.yourco.pdf.filler;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;

class DebugEmbedTest {
    @Test
    void checkFillOutput() throws Exception {
        InputStream tmpl = getClass().getResourceAsStream("/templates/labor_contract_sample.pdf");
        Map<String, String> fields = Map.of(
            "employeeName", "张三",
            "department", "技术研发部",
            "position", "高级工程师",
            "workLocation", "上海市浦东新区张江高科技园区"
        );
        byte[] result = PdfFormFiller.fill(tmpl, fields, false);
        Files.write(Paths.get("/tmp/check_fill.pdf"), result);
        System.out.println("PDF size: " + result.length + " bytes");

        // Check for font data in compressed streams
        checkFontEmbedding(result);

        // Verify field /V values are set - print both string value and raw COS bytes
        try (var doc = Loader.loadPDF(result)) {
            var acroForm = doc.getDocumentCatalog().getAcroForm();
            for (var field : acroForm.getFields()) {
                if (field instanceof PDTextField tf) {
                    String val = tf.getValue();
                    // Print hex bytes of the COSString to confirm UTF-16BE encoding
                    org.apache.pdfbox.cos.COSBase cosV = tf.getCOSObject()
                        .getDictionaryObject(org.apache.pdfbox.cos.COSName.V);
                    String hexBytes = "";
                    if (cosV instanceof org.apache.pdfbox.cos.COSString cs) {
                        byte[] raw = cs.getBytes();
                        StringBuilder sb = new StringBuilder();
                        for (byte b : raw) { sb.append(String.format("%02X ", b)); }
                        hexBytes = sb.toString().trim();
                    }
                    System.out.println("Field '" + tf.getFullyQualifiedName()
                        + "' len=" + val.length()
                        + " hex=[" + hexBytes + "]");
                }
            }
        }
    }

    private void checkFontEmbedding(byte[] data) throws Exception {
        // Decompress all streams and search for font keywords
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "stream\\r?\\n(.*?)\\r?\\nendstream", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(new String(data, "ISO-8859-1"));
        int i = 0;
        while (m.find()) {
            try {
                byte[] compressed = m.group(1).getBytes("ISO-8859-1");
                byte[] dec = java.util.zip.GZIPInputStream.class.cast(null) == null ?
                    java.util.zip.Inflater.class.getMethod("inflate").invoke(null) == null ? null : null : null;
                // Use Inflater directly
                java.util.zip.Inflater inf = new java.util.zip.Inflater();
                inf.setInput(compressed);
                byte[] buf = new byte[100000];
                int n = inf.inflate(buf);
                String s = new String(buf, 0, n, "ISO-8859-1");
                if (s.contains("CIDFont") || s.contains("FontFile") || s.contains("Identity-H")) {
                    System.out.println("Stream " + i + " has font: " + s.substring(0, Math.min(300, s.length())));
                }
            } catch (Exception ignore) {}
            i++;
        }
    }
}
