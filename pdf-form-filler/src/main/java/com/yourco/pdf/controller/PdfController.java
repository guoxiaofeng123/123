package com.yourco.pdf.controller;

import com.yourco.pdf.filler.PdfFormFiller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PDF 套打 REST 接口。
 *
 * <p>提供预览（inline）和下载（attachment）两个端点，复用同一个 {@link PdfFormFiller}。
 */
@Slf4j
@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    @Value("${pdf.template.labor-contract:/templates/labor_contract.pdf}")
    private String laborContractTemplatePath;

    /**
     * 浏览器内嵌预览接口。
     *
     * <p>POST /api/pdf/fill/preview
     * <p>Body: {"fieldName": "value", ...}
     * <p>Response: application/pdf，Content-Disposition: inline
     */
    @PostMapping("/fill/preview")
    public ResponseEntity<byte[]> preview(
            @RequestBody Map<String, String> fieldValues
    ) throws IOException {
        log.debug("收到预览请求，字段数：{}", fieldValues.size());
        byte[] pdf = fillTemplate(laborContractTemplatePath, fieldValues, true);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .body(pdf);
    }

    /**
     * 触发浏览器下载接口。
     *
     * <p>POST /api/pdf/fill/download
     * <p>Body: {"fieldName": "value", ...}
     * <p>Response: application/pdf，Content-Disposition: attachment
     */
    @PostMapping("/fill/download")
    public ResponseEntity<byte[]> download(
            @RequestBody Map<String, String> fieldValues
    ) throws IOException {
        log.debug("收到下载请求，字段数：{}", fieldValues.size());
        byte[] pdf = fillTemplate(laborContractTemplatePath, fieldValues, true);

        String encodedFilename = URLEncoder.encode("output.pdf", StandardCharsets.UTF_8)
                .replace("+", "%20");
        String contentDisposition = "attachment; filename=\"output.pdf\"; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(pdf);
    }

    /**
     * 从 classpath 读取模板并填充，模板路径通过配置注入，与文件系统解耦。
     */
    private byte[] fillTemplate(
            String templatePath,
            Map<String, String> fieldValues,
            boolean flatten
    ) throws IOException {
        ClassPathResource resource = new ClassPathResource(
                templatePath.startsWith("/") ? templatePath.substring(1) : templatePath);

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "PDF 模板文件不存在：" + templatePath +
                    "，请将模板放置在 src/main/resources" + templatePath);
        }

        try (InputStream templateStream = resource.getInputStream()) {
            return PdfFormFiller.fill(templateStream, fieldValues, flatten);
        }
    }
}
