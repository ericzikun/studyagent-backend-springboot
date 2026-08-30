package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 保存 humanizer 右栏编辑版本请求。
 * <p>
 * body 形如 {@code { "content": { "chunks": [...] } }}，content 即落库 content_json 的 Map JSON。
 */
@Data
public class SaveVerlaHumanizerDocumentEditRequest {

    @NotNull(message = "content is required")
    private Map<String, Object> content;
}
