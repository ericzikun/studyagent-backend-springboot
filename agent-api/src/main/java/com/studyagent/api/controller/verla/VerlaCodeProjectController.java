package com.studyagent.api.controller.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaCodeProjectService;
import com.studyagent.service.application.verla.VerlaCodeProjectService.CodeFile;
import com.studyagent.service.application.verla.VerlaCodeProjectService.CodeProject;
import com.studyagent.service.application.verla.VerlaCodeProjectService.ResolvedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Coding 作业「项目级」只读接口：单文件懒加载 + 整包流式 zip 下载。
 * <p>
 * 文件树不在此处暴露——前端从 {@code GET /v1/verla/conversations/{cid}/artifacts} 列表里
 * {@code assignment_code_project} 行的 {@code bodyOrRef} 直接拿 manifest（见技术方案 §2.2 / §4.2）。
 * <p>
 * 鉴权统一走 {@link VerlaCodeProjectService}（内部 {@code conversationService.getOwned}）。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla")
@RequiredArgsConstructor
public class VerlaCodeProjectController {

    private final VerlaCodeProjectService codeProjectService;

    /**
     * 单文件懒加载。
     * {@code download=1} 时附 {@code Content-Disposition: attachment}，否则 inline 供前端内联渲染。
     */
    @GetMapping("/conversations/{cid}/code-projects/{projectUid}/files")
    public ResponseEntity<Resource> getFile(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @PathVariable String projectUid,
            @RequestParam("relPath") String relPath,
            @RequestParam(value = "download", defaultValue = "0") String download) {
        ensureLogin(clerkUserId);
        ResolvedFile file = codeProjectService.resolveFile(clerkUserId, cid, projectUid, relPath);

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (file.mime() != null && !file.mime().isBlank()) {
                mediaType = MediaType.parseMediaType(file.mime());
            }
        } catch (Exception ignore) {
            // keep octet-stream
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noCache().cachePrivate());
        if (isTruthy(download)) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename()));
        }
        return builder.body(new ByteArrayResource(file.bytes()));
    }

    /**
     * 整个项目流式 zip 下载：边读边压边吐，全程不落盘。
     */
    @GetMapping("/conversations/{cid}/code-projects/{projectUid}/archive")
    public ResponseEntity<StreamingResponseBody> archive(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @PathVariable String projectUid) {
        ensureLogin(clerkUserId);
        // 先解析 + 鉴权（异常在进入流式回调前抛出，能正常返回错误响应）。
        CodeProject project = codeProjectService.loadProject(clerkUserId, cid, projectUid);

        StreamingResponseBody body = out -> writeZip(out, project);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(project.rootDir() + ".zip"))
                .body(body);
    }

    private void writeZip(OutputStream out, CodeProject project) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (CodeFile file : project.files()) {
                byte[] bytes = codeProjectService.readBytes(file);
                if (bytes == null) {
                    log.warn("[Verla/code-project] skip unreadable zip entry relPath={}", file.relPath());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(codeProjectService.archiveEntryName(project.rootDir(), file.relPath())));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    /** 同时给 ASCII fallback 与 RFC 5987 {@code filename*}，兼容非 ASCII 文件名。 */
    private static String contentDisposition(String filename) {
        String ascii = filename.replaceAll("[\\r\\n\"]", "_");
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static boolean isTruthy(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
