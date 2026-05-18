package com.yourco.pdf.filler;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF AcroForm 字段名探查工具。
 *
 * <p>开发时运行 main 方法，传入 PDF 模板路径，打印所有字段名、类型和所在页码，
 * 便于组装 fieldValues Map。
 *
 * <p>用法：
 * <pre>
 *   java PdfFormFieldInspector /path/to/template.pdf
 * </pre>
 */
public class PdfFormFieldInspector {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("用法：PdfFormFieldInspector <pdf模板路径>");
            System.exit(1);
        }
        inspect(args[0]);
    }

    /**
     * 探查并打印 PDF 文件中所有 AcroForm 字段信息。
     *
     * @param pdfPath PDF 文件路径
     */
    public static void inspect(String pdfPath) throws IOException {
        File file = new File(pdfPath);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在：" + pdfPath);
        }

        try (PDDocument doc = Loader.loadPDF(file)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                System.out.println("该 PDF 不含 AcroForm 表单域。");
                return;
            }

            // 建立 widget -> 页码映射（widget 挂载在页面的 annotations 上）
            Map<String, Integer> fieldPageMap = buildFieldPageMap(doc);

            List<PDField> allFields = new ArrayList<>();
            collectFields(acroForm.getFields(), allFields);

            System.out.println("=== PDF AcroForm 字段清单 ===");
            System.out.println("模板：" + file.getName());
            System.out.println("共发现 " + allFields.size() + " 个字段：");
            System.out.println();

            // 按页码分组输出
            Map<Integer, List<PDField>> byPage = new TreeMap<>();
            for (PDField field : allFields) {
                int page = fieldPageMap.getOrDefault(field.getFullyQualifiedName(), -1);
                byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(field);
            }

            for (Map.Entry<Integer, List<PDField>> entry : byPage.entrySet()) {
                int pageNum = entry.getKey();
                System.out.println(pageNum > 0 ? "[Page " + pageNum + "]" : "[页码未知]");
                for (PDField field : entry.getValue()) {
                    printField(field);
                }
                System.out.println();
            }
        }
    }

    private static void printField(PDField field) {
        String name = field.getFullyQualifiedName();
        String type = field.getClass().getSimpleName();
        String detail = "";

        if (field instanceof PDCheckBox checkBox) {
            String onValue = checkBox.getOnValue();
            detail = "checked=/" + (onValue != null ? onValue : "Yes") + " unchecked=/Off";
        } else if (field instanceof PDComboBox comboBox) {
            List<String> options = comboBox.getOptions();
            if (!options.isEmpty()) {
                detail = "options=" + options;
            }
        } else if (field instanceof PDListBox listBox) {
            List<String> options = listBox.getOptions();
            if (!options.isEmpty()) {
                detail = "options=" + options;
            }
        } else if (field instanceof PDTextField) {
            detail = "（文本域）";
        }

        System.out.printf("  %-30s %-20s %s%n", name, type, detail);
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
     * 遍历 AcroForm 所有字段的 widget，建立字段名 -> 页码映射。
     * PDFBox 3.x 中 PDAnnotationWidget 不再暴露 getField()，改为从字段侧遍历 widget。
     */
    private static Map<String, Integer> buildFieldPageMap(PDDocument doc) throws IOException {
        Map<String, Integer> map = new HashMap<>();

        // 建立页面对象 -> 页码的反向索引，用于 O(1) 查找
        Map<PDPage, Integer> pageIndexMap = new HashMap<>();
        int idx = 1;
        for (PDPage page : doc.getPages()) {
            pageIndexMap.put(page, idx++);
        }

        PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
        if (acroForm == null) {
            return map;
        }

        List<PDField> allFields = new ArrayList<>();
        collectFields(acroForm.getFields(), allFields);

        for (PDField field : allFields) {
            String name = field.getFullyQualifiedName();
            for (PDAnnotationWidget widget : field.getWidgets()) {
                PDPage widgetPage = widget.getPage();
                if (widgetPage != null) {
                    Integer pageNum = pageIndexMap.get(widgetPage);
                    if (pageNum != null) {
                        map.putIfAbsent(name, pageNum);
                        break;
                    }
                }
            }
        }
        return map;
    }
}
