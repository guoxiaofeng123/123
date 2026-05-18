package com.yourco.pdf.filler;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfFormFillerTest {

    private static final String TEMPLATE_PATH = "/templates/labor_contract_sample.pdf";
    private static Path testOutputDir;

    @BeforeAll
    static void setUp() {
        String outputDirProp = System.getProperty("testOutputDir",
                "target/test-output");
        testOutputDir = Paths.get(outputDirProp);
        try {
            Files.createDirectories(testOutputDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建测试输出目录：" + testOutputDir, e);
        }
    }

    /**
     * 验证中文字体文件已正确打包到 classpath。
     * 若此测试失败，请将 NotoSansSC-Regular.ttf 放置在 src/main/resources/fonts/。
     */
    @Test
    void testFontAccessible() {
        InputStream fontStream = PdfFormFiller.class
                .getResourceAsStream("/fonts/NotoSansSC-Regular.ttf");
        assertNotNull(fontStream,
                "字体文件未打包进 classpath，请检查 src/main/resources/fonts/NotoSansSC-Regular.ttf 是否存在");
    }

    /**
     * 主集成测试：填充劳动合同模板，验证输出字节数组非空，并写出到 target/test-output/ 供人工校验。
     */
    @Test
    void testFillLaborContract() throws IOException {
        InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_PATH);
        assertNotNull(templateStream, "测试模板不存在：" + TEMPLATE_PATH);

        Map<String, String> fieldValues = buildSampleFieldValues();

        byte[] result = PdfFormFiller.fill(templateStream, fieldValues, true);

        assertNotNull(result, "填充结果不应为 null");
        assertTrue(result.length > 0, "填充结果字节数组不应为空");

        // 写出文件供人工校验渲染效果
        Path outputFile = testOutputDir.resolve("labor_contract_filled.pdf");
        Files.write(outputFile, result);
        System.out.println("填充结果已输出：" + outputFile.toAbsolutePath());

        // 验证输出是合法的 PDF
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertNotNull(doc, "输出结果应为合法 PDF");
            assertTrue(doc.getNumberOfPages() > 0, "输出 PDF 应至少有一页");
        }
    }

    /**
     * 验证 flatten=false 时表单域仍然可编辑（AcroForm 存在且字段数量大于 0）。
     */
    @Test
    void testFillWithoutFlatten() throws IOException {
        InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_PATH);
        assertNotNull(templateStream, "测试模板不存在：" + TEMPLATE_PATH);

        Map<String, String> fieldValues = buildSampleFieldValues();
        byte[] result = PdfFormFiller.fill(templateStream, fieldValues, false);

        assertNotNull(result);
        assertTrue(result.length > 0);

        try (PDDocument doc = Loader.loadPDF(result)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertNotNull(acroForm, "未扁平化的 PDF 应保留 AcroForm");
            assertFalse(acroForm.getFields().isEmpty(), "AcroForm 应包含字段");
        }

        Path outputFile = testOutputDir.resolve("labor_contract_editable.pdf");
        Files.write(outputFile, result);
        System.out.println("可编辑 PDF 已输出：" + outputFile.toAbsolutePath());
    }

    /**
     * 验证 fieldValues 中存在模板没有的字段时，静默跳过不抛异常。
     */
    @Test
    void testUnknownFieldsAreSkipped() throws IOException {
        InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_PATH);
        assertNotNull(templateStream);

        Map<String, String> fieldValues = new HashMap<>();
        fieldValues.put("nonExistentField_xyz", "some value");
        fieldValues.put("employeeName", "张三");

        // 不应抛出异常
        assertDoesNotThrow(() -> PdfFormFiller.fill(templateStream, fieldValues, true));
    }

    /**
     * 验证对非 AcroForm PDF 抛出 IllegalArgumentException。
     */
    @Test
    void testThrowsOnNonAcroFormPdf() throws IOException {
        // 使用程序动态创建一个无 AcroForm 的 PDF
        byte[] plainPdfBytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);
            plainPdfBytes = baos.toByteArray();
        }

        InputStream plainStream = new java.io.ByteArrayInputStream(plainPdfBytes);

        assertThrows(IllegalArgumentException.class, () ->
                PdfFormFiller.fill(plainStream, Map.of("field", "value"), true),
                "无 AcroForm 的 PDF 应抛出 IllegalArgumentException");
    }

    private Map<String, String> buildSampleFieldValues() {
        Map<String, String> values = new HashMap<>();
        values.put("employeeName", "张三");
        values.put("employeeId", "EMP20240001");
        values.put("contractStart", "2024-01-01");
        values.put("contractEnd", "2026-12-31");
        values.put("isFullTime", "true");
        values.put("baseSalary", "15000.00");
        values.put("department", "技术研发部");
        values.put("position", "高级工程师");
        values.put("workLocation", "上海市浦东新区张江高科技园区");
        return values;
    }
}
