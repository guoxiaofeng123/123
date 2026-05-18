package com.yourco.pdf.filler;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 生成测试用 AcroForm PDF 模板。
 *
 * <p>此工具类仅在开发阶段使用，用于生成 src/test/resources/templates/labor_contract_sample.pdf。
 * 生成的 PDF 包含与 PdfFormFillerTest 匹配的表单域。
 */
public class GenerateTestTemplate {

    public static void main(String[] args) throws IOException {
        Path outputPath = Paths.get(
                "src/test/resources/templates/labor_contract_sample.pdf");
        generateTemplate(outputPath.toString());
        System.out.println("测试模板已生成：" + outputPath.toAbsolutePath());
    }

    public static void generateTemplate(String outputPath) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            // Page 1
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);

            // Page 2
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDResources resources = new PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), font);
            acroForm.setDefaultResources(resources);
            acroForm.setDefaultAppearance("/Helv 12 Tf 0 g");

            // Page 1 字段
            addTextField(doc, acroForm, page1, "employeeName",   50, 750, 200, 20);
            addTextField(doc, acroForm, page1, "employeeId",     50, 720, 200, 20);
            addTextField(doc, acroForm, page1, "contractStart",  50, 690, 150, 20);
            addTextField(doc, acroForm, page1, "contractEnd",    50, 660, 150, 20);
            addCheckBox (doc, acroForm, page1, "isFullTime",     50, 630, 20,  20);
            addTextField(doc, acroForm, page1, "department",     50, 600, 200, 20);
            addTextField(doc, acroForm, page1, "position",       50, 570, 200, 20);
            addTextField(doc, acroForm, page1, "workLocation",   50, 540, 300, 20);

            // Page 2 字段
            addTextField(doc, acroForm, page2, "baseSalary",     50, 750, 150, 20);

            // 页面上添加标签文字（ASCII，避免需要中文字体）
            addLabels(doc, page1, font);

            doc.save(outputPath);
        }
    }

    private static void addTextField(
            PDDocument doc, PDAcroForm acroForm, PDPage page,
            String fieldName, float x, float y, float width, float height
    ) throws IOException {
        PDTextField field = new PDTextField(acroForm);
        field.setPartialName(fieldName);
        field.setDefaultAppearance("/Helv 12 Tf 0 g");

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, width, height));
        widget.setPage(page);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }

    private static void addCheckBox(
            PDDocument doc, PDAcroForm acroForm, PDPage page,
            String fieldName, float x, float y, float width, float height
    ) throws IOException {
        PDCheckBox field = new PDCheckBox(acroForm);
        field.setPartialName(fieldName);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, width, height));
        widget.setPage(page);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }

    private static void addLabels(PDDocument doc, PDPage page, PDType1Font font)
            throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(50, 780);
            cs.showText("Labor Contract - Test Template");
            cs.newLineAtOffset(0, -30);
            cs.showText("Employee Name:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Employee ID:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Contract Start:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Contract End:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Full Time:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Department:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Position:");
            cs.newLineAtOffset(0, -30);
            cs.showText("Work Location:");
            cs.endText();
        }
    }
}
