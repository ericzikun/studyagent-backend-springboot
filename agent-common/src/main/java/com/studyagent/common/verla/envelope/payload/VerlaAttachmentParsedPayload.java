package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * {@code ATTACHMENT_PARSED} 事件 payload。
 * <p>
 * Py 在解析过程中用同一个 eventType 发送多条事件，状态字段区分阶段：
 * <ul>
 *   <li>{@code PARSING}：进度更新（progress 0~100）</li>
 *   <li>{@code PARSED}：完成；带 primaryArtifactUid + summary</li>
 *   <li>{@code FAILED}：失败；带 errorCode/errorMessage</li>
 * </ul>
 * 详见 docs/V2/5.1 §4.3。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaAttachmentParsedPayload {

    /** 业务唯一 ID（att_*），等同 verla_attachments.object_id */
    private String objectId;

    /** PARSING / PARSED / FAILED */
    private String status;

    /** 0~100，仅 PARSING 阶段提供 */
    private Integer progress;

    /** PARSED 时短摘要 */
    private String summary;

    /** PARSED 时主产物（markdown 全文）的 artifact_uid */
    private String primaryArtifactUid;

    /** PARSED 时所有产出 artifact_uid 列表（含 markdown / 图片切片 / 大纲等） */
    private List<String> artifactUids;

    private String errorCode;
    private String errorMessage;

    /** page_count / image_size / ocr 等元信息 */
    private Map<String, Object> meta;

    /** PARSED 时携带的解析全文缓存（SQL 042） */
    private String markdownContent;

    /** PARSED 时携带的图片元数据 JSON 数组字符串（SQL 042） */
    private String imagesJson;
}
