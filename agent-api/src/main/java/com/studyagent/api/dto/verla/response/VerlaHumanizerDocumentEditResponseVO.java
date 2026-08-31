package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * humanizer 右栏编辑版本读取响应。
 * <p>
 * exists=false 表示该 humanizer 结果尚无保存过的编辑；content 为 content_json 的 Map JSON。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaHumanizerDocumentEditResponseVO {

    private String conversationId;
    private String artifactUid;

    /** 该结果是否保存过编辑。 */
    private Boolean exists;

    /** content_json 的 Map JSON；exists=false 或解析失败时为 null。 */
    private Map<String, Object> content;

    /** 最后编辑时间（保存行 updated_at）；exists=false 时为 null。 */
    private LocalDateTime updatedAt;
}
