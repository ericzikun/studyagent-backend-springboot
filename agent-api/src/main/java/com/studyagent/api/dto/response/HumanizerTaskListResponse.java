package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务列表分页响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HumanizerTaskListResponse {

    private List<HumanizerTaskItemResponse> items;
    private int page;
    private int size;
    private long total;
    private int totalPages;
}
