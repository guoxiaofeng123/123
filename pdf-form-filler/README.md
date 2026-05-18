# PDF AcroForm 套打模块

基于 Apache PDFBox 3.x 的 PDF AcroForm 字段填充组件，为金蝶 s-HR 平台二开提供原子化的"PDF 套打"能力。

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.2.5 |
| Apache PDFBox | 3.0.2 |
| Maven | 3.8+ |

---

## 快速开始

### 1. 放置字体文件（必须）

中文渲染依赖 Noto Sans SC 字体，需手动下载并放置：

```
src/main/resources/fonts/NotoSansSC-Regular.ttf
```

**下载渠道（二选一）：**
- Google Fonts：https://fonts.google.com/noto/specimen/Noto+Sans+SC
- GitHub Release：https://github.com/notofonts/noto-cjk/releases

> 字体文件体积约 10 MB，已加入 `.gitignore`，不提交到版本库。
> 生产部署时需确保字体文件包含在构建产物中（`mvn package` 会自动打包）。

### 2. 放置模板文件（必须）

```
src/main/resources/templates/labor_contract.pdf
```

模板须为含 AcroForm 表单域的 PDF，可用 Adobe Acrobat 或 LibreOffice Draw 创建。

### 3. 构建与运行

```bash
mvn clean package -DskipTests
java -jar target/pdf-form-filler-1.0.0-SNAPSHOT.jar
```

---

## 新模板接入 SOP

### Step 1：创建带 AcroForm 域的 PDF 模板

使用 Adobe Acrobat DC：
1. 打开 PDF → 工具 → 准备表单
2. 添加文本域 / 复选框，**务必设置有意义的字段名**（英文，如 `employeeName`）
3. 保存为 PDF

### Step 2：探查字段名

```bash
# 运行字段探查工具
mvn exec:java -Dexec.mainClass="com.yourco.pdf.filler.PdfFormFieldInspector" \
              -Dexec.args="src/main/resources/templates/your_template.pdf"
```

输出示例：
```
=== PDF AcroForm 字段清单 ===
模板：your_template.pdf
共发现 5 个字段：

[Page 1]
  employeeName          PDTextField     （文本域）
  isFullTime            PDCheckBox      checked=/Yes unchecked=/Off
```

### Step 3：组装 fieldValues Map 并调用填充

```java
Map<String, String> fields = new HashMap<>();
fields.put("employeeName", "张三");
fields.put("isFullTime", "true");

try (InputStream template = new FileInputStream("your_template.pdf")) {
    byte[] pdf = PdfFormFiller.fill(template, fields, true);
    Files.write(Path.of("output.pdf"), pdf);
}
```

### Step 4：在 Controller 中新增端点（可选）

在 `PdfController.java` 中新增 `@PostMapping`，注入新模板路径并复用 `PdfFormFiller.fill()`。

---

## API 接口

### 预览（浏览器内嵌）

```bash
curl -X POST http://localhost:8080/api/pdf/fill/preview \
  -H "Content-Type: application/json" \
  -d '{
    "employeeName": "张三",
    "employeeId": "EMP20240001",
    "contractStart": "2024-01-01",
    "contractEnd": "2026-12-31",
    "isFullTime": "true",
    "baseSalary": "15000.00",
    "department": "技术研发部",
    "position": "高级工程师",
    "workLocation": "上海市浦东新区张江高科技园区"
  }' \
  --output preview.pdf
```

### 下载

```bash
curl -X POST http://localhost:8080/api/pdf/fill/download \
  -H "Content-Type: application/json" \
  -d '{"employeeName": "张三", "baseSalary": "15000.00"}' \
  -O -J
```

---

## 常见问题排查

### Q1：中文显示乱码或空白框

**原因**：字体文件未加载或 DefaultAppearance 格式错误。

**排查步骤**：
1. 确认 `src/main/resources/fonts/NotoSansSC-Regular.ttf` 存在
2. 运行 `testFontAccessible()` 单元测试，确认字体可从 classpath 读取
3. 查看 DEBUG 日志，确认 `DefaultAppearance` 格式为 `/F1 0 Tf 0 g`（有斜杠，字体资源名由 PDFBox 自动分配）
4. 确认 `setValue` 调用在 `setDefaultAppearance` 之后

### Q2：字段名大小写不匹配

AcroForm 字段名**大小写敏感**。使用 `PdfFormFieldInspector` 探查实际字段名，确保 `fieldValues` 的 key 与模板完全一致。

### Q3：`mvn package` 后字体文件损坏（无法加载）

`pom.xml` 已配置 `nonFilteredFileExtension` 排除 `.ttf/.otf/.pdf` 文件的 Maven filtering。
若仍出现问题，检查是否有其他 profile 或 parent pom 覆盖了 maven-resources-plugin 配置。

### Q4：Spring Boot 启动报错 `模板文件不存在`

确认 `src/main/resources/templates/labor_contract.pdf` 存在，或通过环境变量覆盖路径：
```bash
java -jar app.jar --pdf.template.labor-contract=/path/to/your/template.pdf
```

### Q5：PDFBox 3.x 与 2.x 的差异

| 操作 | PDFBox 2.x | PDFBox 3.x |
|------|-----------|-----------|
| 加载 PDF | `PDDocument.load(bytes)` | `Loader.loadPDF(bytes)` |
| 字体嵌入 | `PDType0Font.load(doc, stream, true)` | 相同 |
| AcroForm | `PDDocument.getDocumentCatalog().getAcroForm()` | 相同 |

---

## 项目结构

```
pdf-form-filler/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/yourco/pdf/
    │   │   ├── PdfFormFillerApplication.java   # Spring Boot 入口
    │   │   ├── filler/
    │   │   │   ├── PdfFormFiller.java          # 核心填充器
    │   │   │   └── PdfFormFieldInspector.java  # 字段探查工具
    │   │   └── controller/
    │   │       └── PdfController.java          # REST 接口
    │   └── resources/
    │       ├── application.yml
    │       ├── fonts/
    │       │   └── .gitkeep    # 字体文件手动放置，不入 git
    │       └── templates/
    │           └── .gitkeep    # 模板文件手动放置，不入 git
    └── test/
        ├── java/com/yourco/pdf/filler/
        │   ├── PdfFormFillerTest.java          # 单元测试
        │   └── GenerateTestTemplate.java       # 生成测试模板工具
        └── resources/
            └── templates/
                └── labor_contract_sample.pdf   # 测试用模板（程序生成）
```
