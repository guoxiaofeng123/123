package com.yourco.pdf.filler;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDAppearanceContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF AcroForm 套打核心填充器。
 *
 * <p>使用方法：
 * <pre>
 *   byte[] result = PdfFormFiller.fill(templateStream, fieldValues, true);
 * </pre>
 *
 * <p>依赖字体：{@code /fonts/NotoSansSC-Regular.ttf}（需手动放置在 resources/fonts/）
 */
@Slf4j
public class PdfFormFiller {

    private static final String CHINESE_FONT_PATH = "/fonts/NotoSansSC-Regular.ttf";

    // 识别为"勾选"的值（大小写不敏感）
    private static final Set<String> CHECKED_VALUES = Set.of("true", "1", "yes");

    // 表单域内边距（左/右 padding，单位 pt）
    private static final float TEXT_PADDING_H = 2f;
    // 字号相对于字段高度的比例（0 = 自动）
    private static final float FONT_SIZE_RATIO = 0.65f;

    private PdfFormFiller() {}

    /**
     * 填充 PDF AcroForm 表单域。
     *
     * @param templateStream PDF 模板输入流（方法内部负责关闭）
     * @param fieldValues    字段名 -&gt; 值映射，字段名须与 AcroForm 域名精确匹配
     * @param flatten        true=填充后锁定为普通 PDF；false=保留可编辑表单域
     * @return 填充完成的 PDF 字节数组
     * @throws IOException              读写异常
     * @throws IllegalArgumentException PDF 中不含 AcroForm 表单域
     */
    public static byte[] fill(
            InputStream templateStream,
            Map<String, String> fieldValues,
            boolean flatten
    ) throws IOException {

        // 先读入内存，避免 PDFBox 内部 random-access seek 导致 InputStream 异常
        byte[] templateBytes;
        try (InputStream in = templateStream) {
            templateBytes = in.readAllBytes();
        }

        try (PDDocument doc = Loader.loadPDF(templateBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                throw new IllegalArgumentException(
                        "该 PDF 不含 AcroForm 表单域，无法填充。" +
                        "请确认模板已通过 Adobe Acrobat 或 LibreOffice 添加表单域。");
            }

            // 字体一次性加载，所有字段复用同一个实例
            PDType0Font font = loadChineseFont(doc);

            // 将字体注册到 AcroForm DefaultResources，使 /DA 字符串可以引用
            PDResources acroResources = acroForm.getDefaultResources();
            if (acroResources == null) {
                acroResources = new PDResources();
                acroForm.setDefaultResources(acroResources);
            }
            COSName fontName = acroResources.add(font);
            String defaultAppearance = "/" + fontName.getName() + " 0 Tf 0 g";

            log.debug("中文字体注册为 AcroForm 资源名：{}，DA：{}", fontName.getName(), defaultAppearance);

            // 收集所有叶节点字段（展开嵌套字段组）
            List<PDField> allFields = new ArrayList<>();
            collectFields(acroForm.getFields(), allFields);

            for (PDField field : allFields) {
                String fieldName = field.getFullyQualifiedName();
                if (!fieldValues.containsKey(fieldName)) {
                    log.debug("字段 [{}] 在 fieldValues 中不存在，跳过", fieldName);
                    continue;
                }
                String value = fieldValues.get(fieldName);

                if (field instanceof PDTextField textField) {
                    textField.setDefaultAppearance(defaultAppearance);
                    fillTextField(doc, textField, value, font);
                    log.debug("文本域 [{}] = \"{}\"", fieldName, value);

                } else if (field instanceof PDCheckBox checkBox) {
                    boolean checked = value != null && CHECKED_VALUES.contains(value.toLowerCase());
                    if (checked) {
                        checkBox.check();
                    } else {
                        checkBox.unCheck();
                    }
                    log.debug("复选框 [{}] = {}", fieldName, checked);

                } else if (field instanceof PDComboBox comboBox) {
                    comboBox.setValue(value == null ? "" : value);
                    log.debug("下拉框 [{}] = \"{}\"", fieldName, value);

                } else if (field instanceof PDListBox listBox) {
                    listBox.setValue(value == null ? "" : value);
                    log.debug("列表框 [{}] = \"{}\"", fieldName, value);

                } else {
                    log.warn("字段 [{}] 类型 {} 暂不支持，已跳过",
                            fieldName, field.getClass().getSimpleName());
                }
            }

            // PDAppearanceStream 不持有 PDDocument 引用（PDFormXObject 限制），
            // PDAbstractContentStream.setFont() 因此无法将字体自动加入 doc.fontsToSubset。
            // 在所有 showText() 调用完成后（字形已记录），手动调用 subset() 完成字体嵌入。
            if (font.willBeSubset()) {
                font.subset();
            }

            if (flatten) {
                // 必须在所有字段完成填充后再 flatten
                acroForm.flatten();
                log.debug("AcroForm 已扁平化");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 填充文本域：绕过 PDFBox 内部 appearance 生成逻辑，手动构造 Appearance Stream。
     *
     * <p>PDFBox 自动生成 Appearance 时，Type0/CIDFont 的字体引用可能无法正确注入到
     * Appearance Stream 的局部 Resources，导致中文乱码。手动创建 Appearance Stream
     * 可以确保字体资源与内容流完全对应。
     */
    private static void fillTextField(
            PDDocument doc,
            PDTextField textField,
            String value,
            PDType0Font font
    ) throws IOException {
        String text = value != null ? value : "";

        // 直接写入 /V 条目，使用 UTF-16BE + BOM 编码，确保 CJK 字符正确存储
        // new COSString(String) 使用 PDFDocEncoding，无法表示 CJK；必须传入带 BOM 的 UTF-16BE 字节数组
        byte[] utf16Bytes = text.getBytes(StandardCharsets.UTF_16BE);
        byte[] bomAndBytes = new byte[2 + utf16Bytes.length];
        bomAndBytes[0] = (byte) 0xFE;
        bomAndBytes[1] = (byte) 0xFF;
        System.arraycopy(utf16Bytes, 0, bomAndBytes, 2, utf16Bytes.length);
        textField.getCOSObject().setItem(COSName.V, new COSString(bomAndBytes));

        for (PDAnnotationWidget widget : textField.getWidgets()) {
            PDRectangle rect = widget.getRectangle();
            if (rect == null) {
                continue;
            }

            float w = rect.getWidth();
            float h = rect.getHeight();

            // 创建 Appearance Stream（BBox 从原点开始，与字段尺寸一致）
            PDAppearanceStream ap = new PDAppearanceStream(doc);
            ap.setBBox(new PDRectangle(w, h));
            ap.setMatrix(new AffineTransform()); // 单位矩阵

            // 将字体注册到 Appearance Stream 自身的 Resources
            // 必须在这里单独注册；Appearance 内容流只能引用自身 Resources 中的字体
            PDResources apRes = new PDResources();
            COSName apFontKey = apRes.add(font);
            ap.setResources(apRes);

            // 自动字号：字段高度 × FONT_SIZE_RATIO；垂直居中
            float fontSize = h * FONT_SIZE_RATIO;
            float yOffset = (h - fontSize) / 2f;

            try (PDAppearanceContentStream cs = new PDAppearanceContentStream(ap)) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(TEXT_PADDING_H, yOffset);
                // showText 对 PDType0Font 正确使用双字节 CID 编码，支持完整 Unicode
                cs.showText(text);
                cs.endText();
            }

            // 将新建的 Appearance 设置为 widget 的 Normal Appearance
            PDAppearanceDictionary apDict = widget.getAppearance();
            if (apDict == null) {
                apDict = new PDAppearanceDictionary();
                widget.setAppearance(apDict);
            }
            apDict.setNormalAppearance(ap);
        }
    }

    private static void collectFields(List<PDField> fields, List<PDField> result) {
        for (PDField field : fields) {
            if (field instanceof PDNonTerminalField nonTerminal) {
                collectFields(nonTerminal.getChildren(), result);
            } else {
                result.add(field);
            }
        }
    }

    /**
     * 从 classpath 加载中文字体，embed=true 确保字体完整嵌入输出 PDF。
     * 字体文件须手动放置在 src/main/resources/fonts/NotoSansSC-Regular.ttf。
     */
    private static PDType0Font loadChineseFont(PDDocument doc) throws IOException {
        try (InputStream fontStream = PdfFormFiller.class.getResourceAsStream(CHINESE_FONT_PATH)) {
            if (fontStream == null) {
                throw new IllegalStateException(
                        "中文字体文件未找到：" + CHINESE_FONT_PATH + "\n" +
                        "请将 NotoSansSC-Regular.ttf 放置在 src/main/resources/fonts/ 目录下。\n" +
                        "下载地址：https://fonts.google.com/noto/specimen/Noto+Sans+SC");
            }
            return PDType0Font.load(doc, fontStream, true);
        }
    }
}
