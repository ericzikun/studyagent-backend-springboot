package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
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
                .map(VerlaConversationVO::fromPublic)
                .collect(Collectors.toList());
        return VerlaConversationPageVO.builder()
                .records(vos)
                .total(slice.total())
                .pageNo(slice.pageNo())
                .pageSize(slice.pageSize())
                .build();
    }

    public static VerlaConversationPageVO fromSlice(
            VerlaConversationListSlice slice,
            Map<Long, String> dashboardStatuses) {
        List<VerlaConversationVO> vos = slice.records().stream()
                .map(c -> VerlaConversationVO.from(c, statusFor(c, dashboardStatuses)))
                .collect(Collectors.toList());
        return VerlaConversationPageVO.builder()
                .records(vos)
                .total(slice.total())
                .pageNo(slice.pageNo())
                .pageSize(slice.pageSize())
                .build();
    }

    private static String statusFor(VerlaConversation conversation, Map<Long, String> dashboardStatuses) {
        if (conversation == null || conversation.getId() == null || dashboardStatuses == null) {
            return null;
        }
        return dashboardStatuses.get(conversation.getId());
    }
}
