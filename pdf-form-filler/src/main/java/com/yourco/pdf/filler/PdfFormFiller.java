package com.yourco.pdf.filler;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.cos.COSName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private PdfFormFiller() {}

    /**
     * 填充 PDF AcroForm 表单域。
     *
     * @param templateStream PDF 模板输入流（方法内部负责关闭）
     * @param fieldValues    字段名 -&gt; 值映射，字段名须与 AcroForm 域名精确匹配
     * @param flatten        true=填充后扁平化为普通 PDF（适合存档/打印）；
     *                       false=保留可编辑表单域（适合二次编辑）
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

            // 注册中文字体到 AcroForm DefaultResources（一次性，所有字段复用）
            PDType0Font chineseFont = loadChineseFont(doc);
            PDResources acroResources = acroForm.getDefaultResources();
            if (acroResources == null) {
                acroResources = new PDResources();
                acroForm.setDefaultResources(acroResources);
            }
            COSName fontName = acroResources.add(chineseFont);
            // DefaultAppearance 格式：/字体资源名 字号 Tf 颜色
            // 字号 0 表示自动适应字段高度
            String defaultAppearance = "/" + fontName.getName() + " 0 Tf 0 g";

            log.debug("中文字体注册为资源名：{}，DefaultAppearance：{}", fontName.getName(), defaultAppearance);

            // 遍历所有字段并填充
            fillFields(acroForm.getFields(), fieldValues, defaultAppearance);

            if (flatten) {
                // 必须在所有 setValue 完成后再 flatten，否则填充内容丢失
                acroForm.flatten();
                log.debug("AcroForm 已扁平化");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void fillFields(
            List<PDField> fields,
            Map<String, String> fieldValues,
            String defaultAppearance
    ) throws IOException {
        for (PDField field : fields) {
            // 递归处理嵌套字段组（PDNonTerminalField）
            if (field instanceof PDNonTerminalField nonTerminal) {
                fillFields(nonTerminal.getChildren(), fieldValues, defaultAppearance);
                continue;
            }

            String fieldName = field.getFullyQualifiedName();
            if (!fieldValues.containsKey(fieldName)) {
                log.debug("字段 [{}] 在 fieldValues 中不存在，跳过", fieldName);
                continue;
            }

            String value = fieldValues.get(fieldName);

            if (field instanceof PDTextField textField) {
                // 必须先设置 DefaultAppearance，再 setValue，顺序不能反
                textField.setDefaultAppearance(defaultAppearance);
                textField.setValue(value == null ? "" : value);
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
