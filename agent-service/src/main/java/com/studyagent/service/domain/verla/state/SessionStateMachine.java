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

        // DISPATCHING 行额外接受 AGENT_COMPLETED / AGENT_FAILED / AGENT_CANCELLED / USER_CANCEL：
        // 真实链路通常 DISPATCHING → RUNNING → SUCCEEDED，但存在两类合法跳过 RUNNING 的情况：
        //   1) Plan session：Py 内部很快出意图，可能在 broker confirm 前就直接回 PLAN_INTENT_RESOLVED；
        //      orchestrator.onPlanResolved 会以 AGENT_COMPLETED 推进状态机。
        //   2) Agent session：极端情况下 Py 还没来得及发 AGENT_STARTED 就出错回 AGENT_FAILED，
        //      或用户在 ACK_OK 之后立刻取消（USER_CANCEL）。
        // 补这几条让状态机在合法链路上不抛 IllegalStateException。
        table.put(DISPATCHING, Map.of(
                ACK_OK, DISPATCHING,
                ACK_FAIL, FAILED,
                AGENT_STARTED, RUNNING,
                AGENT_COMPLETED, SUCCEEDED,
                AGENT_FAILED, FAILED,
                AGENT_CANCELLED, CANCELLED,
                USER_CANCEL, CANCELLING,
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
