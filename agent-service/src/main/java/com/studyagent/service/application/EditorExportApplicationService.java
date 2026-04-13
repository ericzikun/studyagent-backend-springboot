package com.studyagent.service.application;

import com.studyagent.service.domain.file.File;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EditorExportApplicationService {

    private static final String TEMP_INPUT_FILENAME = "source.docx";
    private static final String TEMP_OUTPUT_FILENAME = "source.pdf";
    private static final String PDF_COMMAND_TEMPLATE_DEFAULT =
            "soffice --headless --convert-to pdf --outdir {outdir} {input}";

    private final FileApplicationService fileApplicationService;

    @Value("${editor.export.temp-dir:${java.io.tmpdir}/studyagent-editor-export}")
    private String editorExportTempDir;

    @Value("${editor.export.pdf-command-template:" + PDF_COMMAND_TEMPLATE_DEFAULT + "}")
    private String pdfCommandTemplate;

    @Value("${editor.export.timeout-seconds:120}")
    private long timeoutSeconds;

    public ExportedFileResult exportWord(String rawBody, String filename) {
        String normalizedFilename = ensureExtension(defaultFilename(filename, "document"), "docx");
        String objectId = fileApplicationService.uploadFile(rawBody, normalizedFilename);
        return new ExportedFileResult(objectId, buildDownloadUrl(objectId), normalizedFilename);
    }

    public ExportedFileResult convertWordToPdf(String sourceObjectId, String filename) {
        File sourceFile = fileApplicationService.getFileByObjectId(sourceObjectId);
        if (sourceFile == null) {
            throw new IllegalArgumentException("Source file not found: " + sourceObjectId);
        }
        if (!"docx".equalsIgnoreCase(sourceFile.getFileExtension())) {
            throw new IllegalArgumentException("Only DOCX files can be converted to PDF");
        }

        byte[] sourceBytes = fileApplicationService.loadFileContent(sourceFile)
                .orElseThrow(() -> new IllegalStateException("Source file content is empty"));

        String normalizedFilename = ensureExtension(defaultFilename(filename, sourceFile.getOriginalFilename()), "pdf");
        Path workDir = createWorkDir();
        try {
            Path inputFile = workDir.resolve(TEMP_INPUT_FILENAME);
            Files.write(inputFile, sourceBytes);

            executePdfConversion(workDir, inputFile);

            Path outputFile = findOutputPdf(workDir)
                    .orElseThrow(() -> new IllegalStateException("Converted PDF file not found"));
            byte[] pdfBytes = Files.readAllBytes(outputFile);
            String objectId = fileApplicationService.uploadFileBytes(pdfBytes, normalizedFilename);
            return new ExportedFileResult(objectId, buildDownloadUrl(objectId), normalizedFilename);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to convert DOCX to PDF", e);
        } finally {
            cleanupWorkDir(workDir);
        }
    }

    private Path createWorkDir() {
        try {
            Path baseDir = Paths.get(editorExportTempDir);
            Files.createDirectories(baseDir);
            return Files.createDirectories(baseDir.resolve(UUID.randomUUID().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create export temp directory", e);
        }
    }

    private void executePdfConversion(Path workDir, Path inputFile) throws IOException {
        String commandTemplate = StringUtils.hasText(pdfCommandTemplate)
                ? pdfCommandTemplate
                : PDF_COMMAND_TEMPLATE_DEFAULT;
        String command = commandTemplate
                .replace("{workdir}", shellQuote(workDir.toString()))
                .replace("{input}", shellQuote(inputFile.toString()))
                .replace("{outdir}", shellQuote(workDir.toString()))
                .replace("{output}", shellQuote(workDir.resolve(TEMP_OUTPUT_FILENAME).toString()))
                .replace("{input_basename}", TEMP_INPUT_FILENAME)
                .replace("{output_basename}", TEMP_OUTPUT_FILENAME);

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-lc", command);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);

        log.info("开始执行 PDF 转换命令: {}", command);
        Process process = processBuilder.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("PDF conversion interrupted", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("PDF conversion timed out after " + timeoutSeconds + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("PDF conversion failed: " + output);
        }
        log.info("PDF 转换完成: {}", output);
    }

    private Optional<Path> findOutputPdf(Path workDir) throws IOException {
        Path expected = workDir.resolve(TEMP_OUTPUT_FILENAME);
        if (Files.isRegularFile(expected)) {
            return Optional.of(expected);
        }
        try (var stream = Files.list(workDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .findFirst();
        }
    }

    private void cleanupWorkDir(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var stream = Files.walk(workDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除临时导出文件失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("清理临时导出目录失败: {}", workDir, e);
        }
    }

    private String defaultFilename(String filename, String fallback) {
        if (!StringUtils.hasText(filename)) {
            return fallback;
        }
        return filename.trim();
    }

    private String ensureExtension(String filename, String extension) {
        String normalized = filename.trim();
        String expected = "." + extension.toLowerCase();
        if (normalized.toLowerCase().endsWith(expected)) {
            return normalized;
        }

        int lastDot = normalized.lastIndexOf('.');
        if (lastDot > 0) {
            normalized = normalized.substring(0, lastDot);
        }
        return normalized + expected;
    }

    private String buildDownloadUrl(String objectId) {
        return "/v1/file/download/" + objectId;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public record ExportedFileResult(String objectId, String downloadUrl, String filename) {
    }
}
