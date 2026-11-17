package com.studyagent.infra.mapper.verla;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaEventInboxMapperSqlTest {

    @Test
    void dashboardQueryRanksIdsBeforeFetchingPayload() throws Exception {
        Method method = VerlaEventInboxMapper.class.getMethod(
                "selectRecentProcessedByConversationIds", List.class, int.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertFalse(sql.contains("SELECT *"),
                "窗口排序不能再 SELECT *，否则大 payload 会撑爆 sort buffer");
        assertTrue(sql.contains("SELECT id, conversation_id"));
        assertTrue(sql.contains("JOIN verla_event_inbox e ON e.id = ranked.id"));
    }
}
