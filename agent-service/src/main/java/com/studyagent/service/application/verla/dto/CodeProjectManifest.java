package com.studyagent.service.application.verla.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Coding 作业项目清单（manifest），存放于 {@code assignment_code_project} artifact 的
 * {@code bodyOrRef}。由 Python 侧 {@code assignment_artifacts._build_project_artifacts} 生成。
 * <p>
 * 注意：{@link FileEntry#getArtifactUid()} 是 Python 侧的 <b>局部 uid</b>
 * （{@code code_project_<hash>}），并非落库后的全局 {@code artifactUid}
 * （{@code artifact_<cid>_<tid>_<sid>_code_project_<hash>}）。因此 Java 不可用它做行查询，
 * 应按 {@code relPath} 在同 session 的 {@code assignment_code_file} 行里解析。
 * <p>
 * 详见 docs/coding作业项目级文件树与下载-技术方案.md §2.2 / §4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeProjectManifest {

    private Integer schemaVersion;
    private String projectUid;
    private String rootDir;
    private Integer fileCount;
    private Long totalBytes;
    private List<FileEntry> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileEntry {
        private String relPath;
        /** Python 局部 uid，仅供参考，不可用于行查询（见类注释）。 */
        private String artifactUid;
        private String language;
        private Long sizeBytes;
        private boolean binary;
    }
}
