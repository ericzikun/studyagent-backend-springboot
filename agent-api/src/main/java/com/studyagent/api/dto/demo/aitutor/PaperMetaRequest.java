package com.studyagent.api.dto.demo.aitutor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class PaperMetaRequest {
    /** paperMeta：兼容对象或 JSON 字符串两种入参 */
    private JsonNode paperMeta;
}
