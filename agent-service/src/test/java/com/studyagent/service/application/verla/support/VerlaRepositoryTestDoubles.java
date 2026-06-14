package com.studyagent.service.application.verla.support;

import com.studyagent.service.domain.verla.VerlaClarifyForm;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试桩公共实现，减少 Fake Repository 重复代码。
 */
public final class VerlaRepositoryTestDoubles {

    private VerlaRepositoryTestDoubles() {
    }

    public static Map<Long, List<VerlaEventInbox>> batchRecentProcessedEvents(
            List<Long> conversationIds,
            int limitPerConversation,
            Function<Long, List<VerlaEventInbox>> loader) {
        Map<Long, List<VerlaEventInbox>> result = new LinkedHashMap<>();
        if (conversationIds == null) {
            return result;
        }
        for (Long conversationId : conversationIds) {
            if (conversationId != null) {
                result.put(conversationId, loader.apply(conversationId));
            }
        }
        return result;
    }

    public static Map<Long, List<VerlaTurn>> batchRecentTurns(
            List<Long> conversationIds,
            Function<Long, List<VerlaTurn>> loader) {
        Map<Long, List<VerlaTurn>> result = new LinkedHashMap<>();
        if (conversationIds == null) {
            return result;
        }
        for (Long conversationId : conversationIds) {
            if (conversationId != null) {
                result.put(conversationId, loader.apply(conversationId));
            }
        }
        return result;
    }

    public static Map<Long, List<VerlaClarifyForm>> batchOpenClarifyForms(
            List<Long> conversationIds,
            Function<Long, List<VerlaClarifyForm>> loader) {
        Map<Long, List<VerlaClarifyForm>> result = new LinkedHashMap<>();
        if (conversationIds == null) {
            return result;
        }
        for (Long conversationId : conversationIds) {
            if (conversationId != null) {
                result.put(conversationId, loader.apply(conversationId));
            }
        }
        return result;
    }

    public static Map<Long, List<VerlaSession>> batchSessionsByTurn(
            List<Long> turnIds,
            Function<Long, List<VerlaSession>> loader) {
        Map<Long, List<VerlaSession>> result = new LinkedHashMap<>();
        if (turnIds == null) {
            return result;
        }
        for (Long turnId : turnIds) {
            if (turnId != null) {
                result.put(turnId, loader.apply(turnId));
            }
        }
        return result;
    }
}
