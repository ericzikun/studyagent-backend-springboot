package com.studyagent.api.dto.admin.response;

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
public class AdminConversationPageVO {

    private List<AdminConversationRowVO> records;
    private long total;
    private int pageNo;
    private int pageSize;

    public static AdminConversationPageVO fromSlice(
            VerlaConversationListSlice slice,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, String> ownerDisplayNameByClerkUserId) {
        if (slice == null) {
            return AdminConversationPageVO.builder()
                    .records(List.of())
                    .total(0L)
                    .pageNo(1)
                    .pageSize(20)
                    .build();
        }
        List<AdminConversationRowVO> records = slice.records() == null
                ? List.of()
                : slice.records().stream()
                .map(conversation -> toRow(
                        conversation,
                        dashboardStatusByConversationId,
                        ownerDisplayNameByClerkUserId))
                .collect(Collectors.toList());
        return AdminConversationPageVO.builder()
                .records(records)
                .total(slice.total())
                .pageNo(slice.pageNo())
                .pageSize(slice.pageSize())
                .build();
    }

    private static AdminConversationRowVO toRow(
            VerlaConversation conversation,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, String> ownerDisplayNameByClerkUserId) {
        String dashboardStatus = dashboardStatusByConversationId == null
                ? null
                : dashboardStatusByConversationId.get(conversation.getId());
        String ownerDisplayName = ownerDisplayNameByClerkUserId == null
                ? null
                : ownerDisplayNameByClerkUserId.get(conversation.getUserId());
        return AdminConversationRowVO.from(conversation, dashboardStatus, ownerDisplayName);
    }
}
