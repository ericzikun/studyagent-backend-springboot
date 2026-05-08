package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessagePageVO {

    private List<VerlaMessageVO> items;

    /** 下一页游标（取本页最小 messageId）；为空表示无更多 */
    private Long nextCursor;
}
