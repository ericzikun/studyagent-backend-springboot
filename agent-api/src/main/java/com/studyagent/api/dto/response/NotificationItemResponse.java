package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationItemResponse {

    private String id;
    private String title;
    private String message;
    private String content;
    /** Unix 秒级时间戳 */
    private Long createdAt;
    private Boolean isRead;
}
