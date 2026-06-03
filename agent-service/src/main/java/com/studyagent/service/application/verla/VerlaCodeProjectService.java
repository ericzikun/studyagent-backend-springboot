package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.dto.CodeProjectManifest;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coding 作业「项目级」只读读取服务：解析 manifest、按 relPath 取单文件、枚举整包文件。
 * <p>
 * 文件树本身不由本服务对外暴露——前端直接从 {@code conversations/{cid}/artifacts} 列表里
 * {@code assignment_code_project} 行的 {@code bodyOrRef} 拿 manifest（见技术方案 §2.2）。
 * 本服务只服务于单文件懒加载与整包 zip 下载。
 * <p>
 * 关键：manifest 内的 {@code files[].artifactUid} 是 Python 局部 uid，<b>不可</b>用于行查询；
 * 文件行通过「同 session + kind=assignment_code_file + metaJson.relPath」解析。
 * <p>
 * 详见 docs/coding作业项目级文件树与下载-技术方案.md §4。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaCodeProjectService {

    static final String KIND_PROJECT = "assignment_code_project";
    static final String KIND_FILE = "assignment_code_file";
    private static final String OSS_SCHEME = "oss://";

    private final VerlaConversationService conversationService;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaAttachmentService attachmentService;
    private final ObjectMapper objectMapper;

    /**
     * 加载一个 coding 项目：校验所有权、解析 manifest、把同 session 的文件行按 relPath 建索引。
     */
    public CodeProject loadProject(String clerkUserId, Long conversationId, String projectUid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, conversationId);

        if (!StringUtils.hasText(projectUid)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "projectUid required");
        }
        VerlaArtifact manifestRow = artifactRepository.findByUid(projectUid);
        if (manifestRow == null
                || !KIND_PROJECT.equals(manifestRow.getKind())
                || manifestRow.getConversationId() == null
                || !manifestRow.getConversationId().equals(conversationId)) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "code project");
        }

        CodeProjectManifest manifest = parseManifest(manifestRow.getBodyOrRef());
        String rootDir = StringUtils.hasText(manifest.getRootDir()) ? manifest.getRootDir() : "project";

        Map<String, VerlaArtifact> rowsByRelPath = indexFilesBySession(
                manifestRow.getSessionId(), manifest.getProjectUid());

        List<CodeFile> files = new ArrayList<>();
        if (manifest.getFiles() != null) {
            for (CodeProjectManifest.FileEntry entry : manifest.getFiles()) {
                String relPath = sanitizeRelPath(entry.getRelPath());
                VerlaArtifact row = rowsByRelPath.get(relPath);
                if (row == null) {
                    log.warn("[Verla/code-project] manifest entry missing row projectUid={} relPath={}",
                            projectUid, relPath);
                    continue;
                }
                files.add(new CodeFile(relPath, entry.isBinary(), entry.getLanguage(), row));
            }
        }
        return new CodeProject(rootDir, files);
    }

    /** 单文件懒加载：返回文件名 / mime / 字节。 */
    public ResolvedFile resolveFile(String clerkUserId, Long conversationId, String projectUid, String relPath) {
        String safeRelPath = sanitizeRelPath(relPath);
        CodeProject project = loadProject(clerkUserId, conversationId, projectUid);
        CodeFile file = project.files().stream()
                .filter(f -> f.relPath().equals(safeRelPath))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiCode.TASK_NOT_FOUND, "file"));

        byte[] bytes = readBytes(file);
        if (bytes == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "file content");
        }
        String filename = lastSegment(safeRelPath);
        String mime = StringUtils.hasText(file.row().getMime())
                ? file.row().getMime()
                : "application/octet-stream";
        return new ResolvedFile(filename, mime, bytes);
    }

    /** 读取单个项目文件字节：二进制走附件，文本走 bodyOrRef。 */
    public byte[] readBytes(CodeFile file) {
        VerlaArtifact row = file.row();
        if (file.binary()) {
            String objectId = resolveObjectId(row);
            if (!StringUtils.hasText(objectId)) {
                log.warn("[Verla/code-project] binary file without objectId uid={}", row.getArtifactUid());
                return null;
            }
            return attachmentService.loadAttachmentBytes(objectId);
        }
        String body = row.getBodyOrRef();
        return body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
    }

    /** zip entry 名：{@code <rootDir>/<relPath>}，已消毒。 */
    public String archiveEntryName(String rootDir, String relPath) {
        return rootDir + "/" + sanitizeRelPath(relPath);
    }

    // ----------------------------------------------------------------
    // internals
    // ----------------------------------------------------------------

    private Map<String, VerlaArtifact> indexFilesBySession(Long sessionId, String localProjectUid) {
        Map<String, VerlaArtifact> map = new HashMap<>();
        if (sessionId == null) {
            return map;
        }
        for (VerlaArtifact row : artifactRepository.findBySession(sessionId)) {
            if (!KIND_FILE.equals(row.getKind())) {
                continue;
            }
            JsonNode meta = parseMeta(row.getMetaJson());
            if (meta == null) {
                continue;
            }
            // 同 conversation 多个 coding 作业时，靠 session 区分已足够；projectUid 再做一层兜底校验。
            if (StringUtils.hasText(localProjectUid)) {
                String rowProjectUid = meta.path("projectUid").asText(null);
                if (rowProjectUid != null && !localProjectUid.equals(rowProjectUid)) {
                    continue;
                }
            }
            String relPath = meta.path("relPath").asText(null);
            if (!StringUtils.hasText(relPath)) {
                continue;
            }
            map.put(sanitizeRelPath(relPath), row);
        }
        return map;
    }

    private CodeProjectManifest parseManifest(String bodyOrRef) {
        if (!StringUtils.hasText(bodyOrRef)) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "code project manifest");
        }
        try {
            return objectMapper.readValue(bodyOrRef, CodeProjectManifest.class);
        } catch (Exception e) {
            log.warn("[Verla/code-project] manifest parse failed: {}", e.getMessage());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "manifest parse failed");
        }
    }

    private JsonNode parseMeta(String metaJson) {
        if (!StringUtils.hasText(metaJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(metaJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveObjectId(VerlaArtifact row) {
        if (StringUtils.hasText(row.getSourceObjectId())) {
            return row.getSourceObjectId().trim();
        }
        String ref = row.getContentRef();
        if (StringUtils.hasText(ref) && ref.startsWith(OSS_SCHEME)) {
            return ref.substring(OSS_SCHEME.length()).trim();
        }
        return null;
    }

    /**
     * 拒绝路径穿越：去掉首尾空白；拒绝绝对路径、{@code ..} 段、反斜杠、空字节；规范化重复斜杠。
     */
    static String sanitizeRelPath(String relPath) {
        if (!StringUtils.hasText(relPath)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "relPath required");
        }
        String value = relPath.trim().replace('\\', '/');
        if (value.indexOf('\0') >= 0 || value.startsWith("/")) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "invalid relPath");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BusinessException(ApiCode.PARAM_ERROR, "invalid relPath");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "invalid relPath");
        }
        return String.join("/", segments);
    }

    private static String lastSegment(String relPath) {
        int idx = relPath.lastIndexOf('/');
        return idx >= 0 ? relPath.substring(idx + 1) : relPath;
    }

    private static void ensureLogin(String clerkUserId) {
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }

    // ----------------------------------------------------------------
    // value holders
    // ----------------------------------------------------------------

    /** 一个已校验、已索引的 coding 项目。 */
    public record CodeProject(String rootDir, List<CodeFile> files) {}

    /** 项目内单文件：relPath + 是否二进制 + 落库行。 */
    public record CodeFile(String relPath, boolean binary, String language, VerlaArtifact row) {}

    /** 单文件下载结果。 */
    public record ResolvedFile(String filename, String mime, byte[] bytes) {}
}
