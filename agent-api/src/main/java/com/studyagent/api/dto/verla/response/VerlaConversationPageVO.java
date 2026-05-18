package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationPageVO {

    private List<VerlaConversationVO> records;
    private long total;
    private int pageNo;
    private int pageSize;

    public static VerlaConversationPageVO fromSlice(VerlaConversationListSlice slice) {
        List<VerlaConversationVO> vos = slice.records().stream()
                .map(VerlaConversationVO::from)
                .collect(Collectors.toList());
        return VerlaConversationPageVO.builder()
                .records(vos)
                .total(slice.total())
                .pageNo(slice.pageNo())
                .pageSize(slice.pageSize())
                .build();
    }
}
