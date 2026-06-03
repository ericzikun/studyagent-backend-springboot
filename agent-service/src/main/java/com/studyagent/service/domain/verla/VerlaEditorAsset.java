package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 编辑器内部素材资源域对象。
 * 只服务编辑器渲染/保存/恢复，不参与作业附件语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorAsset {

    private Long id;
    private String assetId;
    private Long conversationId;
    private String artifactUid;
    private String editorKind;
    private String assetRole;
    private String userId;
    private String filename;
    private String mime;
    private Long sizeBytes;
    private String storageUri;
    private String ossKey;
    private String checksumSha256;
    private String status;
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_FINALIZED = "FINALIZED";

    public static final String ROLE_INLINE_IMAGE = "inline_image";
    public static final String ROLE_SLIDE_IMAGE = "slide_image";
    public static final String ROLE_SLIDE_BACKGROUND = "slide_background";
    public static final String ROLE_EDITOR_FILE = "editor_file";

    public static final String KIND_DOCUMENT = "document";
    public static final String KIND_SLIDES = "slides";
    public static final String KIND_CODE = "code";
}
