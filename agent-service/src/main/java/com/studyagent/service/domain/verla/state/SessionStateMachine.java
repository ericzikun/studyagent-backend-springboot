package com.studyagent.service.domain.verla.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

import static com.studyagent.service.domain.verla.state.SessionEvent.*;
import static com.studyagent.service.domain.verla.state.SessionStatus.*;

/**
 * Session 状态机
 * <p>
 * <b>所有 session.status 变更必须经过本类</b>。
 * 与 verla_sessions.status 字面量保持一致；STREAMING 合并入 RUNNING。
 */
@Slf4j
@Component
public class SessionStateMachine {

    private static final Map<SessionStatus, Map<SessionEvent, SessionStatus>> TABLE = buildTable();

    private static Map<SessionStatus, Map<SessionEvent, SessionStatus>> buildTable() {
        Map<SessionStatus, Map<SessionEvent, SessionStatus>> table = new EnumMap<>(SessionStatus.class);

        table.put(CREATED, Map.of(
                DISPATCH, DISPATCHING));

        table.put(DISPATCHING, Map.of(
                ACK_OK, DISPATCHING,
                ACK_FAIL, FAILED,
                AGENT_STARTED, RUNNING,
                WATCHDOG_TIMEOUT, FAILED));

        table.put(RUNNING, Map.of(
                STREAM_TICK, RUNNING,
                AGENT_COMPLETED, SUCCEEDED,
                AGENT_FAILED, FAILED,
                AGENT_CANCELLED, CANCELLED,
                USER_CANCEL, CANCELLING,
                WATCHDOG_TIMEOUT, FAILED));

        table.put(CANCELLING, Map.of(
                AGENT_CANCELLED, CANCELLED,
                AGENT_COMPLETED, CANCELLED,
                AGENT_FAILED, CANCELLED,
                WATCHDOG_TIMEOUT, CANCELLED));

        return table;
    }

    /**
     * 计算下一个状态
     *
     * @return 目标状态；若已是终态则返回 current（幂等忽略）
     * @throws IllegalStateException 非法转换
     */
    public SessionStatus next(SessionStatus current, SessionEvent event) {
        if (current.isTerminal()) {
            log.warn("Session already terminal {}, ignore event {}", current, event);
            return current;
        }
        Map<SessionEvent, SessionStatus> row = TABLE.get(current);
        SessionStatus next = row == null ? null : row.get(event);
        if (next == null) {
            throw new IllegalStateException(
                    "Invalid session transition: " + current + " -- " + event);
        }
        return next;
    }
}
