package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaConversation;

import java.util.List;

public record VerlaConversationListSlice(
        List<VerlaConversation> records,
        long total,
        int pageNo,
        int pageSize
) {}
