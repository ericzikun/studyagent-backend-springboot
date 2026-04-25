package com.studyagent.service.domain.verla.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

import static com.studyagent.service.domain.verla.state.TurnEvent.*;
import static com.studyagent.service.domain.verla.state.TurnStatus.*;

/**
 * Turn 状态机
 * <p>
 * <b>所有 turn.status 变更必须经过本类</b>，禁止直接 setStatus。
 * 状态表与 verla_turns.status 字面量保持一致。
 */
@Slf4j
@Component
public class TurnStateMachine {

    private static final Map<TurnStatus, Map<TurnEvent, TurnStatus>> TABLE = buildTable();

    private static Map<TurnStatus, Map<TurnEvent, TurnStatus>> buildTable() {
        Map<TurnStatus, Map<TurnEvent, TurnStatus>> table = new EnumMap<>(TurnStatus.class);

        table.put(CREATED, Map.of(
                SUBMIT, PLANNING,
                SKIP_PLAN, DISPATCHING));

        table.put(PLANNING, Map.of(
                PLAN_OK, DISPATCHING,
                PLAN_CLARIFY, AWAITING_CLARIFY,
                PLAN_FAIL, FAILED,
                USER_CANCEL, CANCELLING));

        table.put(AWAITING_CLARIFY, Map.of(
                SUBMIT, PLANNING,
                USER_CANCEL, CANCELLED));

        table.put(DISPATCHING, Map.of(
                START_AGENT, RUNNING_AGENT,
                USER_CANCEL, CANCELLING,
                AGENT_FAIL, FAILED));

        table.put(RUNNING_AGENT, Map.of(
                AGENT_OK, COMPLETED,
                AGENT_FAIL, FAILED,
                USER_CANCEL, CANCELLING,
                WATCHDOG_TIMEOUT, FAILED));

        table.put(CANCELLING, Map.of(
                CANCEL_CONFIRMED, CANCELLED,
                AGENT_OK, CANCELLED,
                AGENT_FAIL, CANCELLED,
                WATCHDOG_TIMEOUT, CANCELLED));

        return table;
    }

    /**
     * 计算下一个状态
     *
     * @param current 当前状态
     * @param event   触发事件
     * @return 目标状态；若已是终态则返回 current（幂等忽略）
     * @throws IllegalStateException 非法转换
     */
    public TurnStatus next(TurnStatus current, TurnEvent event) {
        if (current.isTerminal()) {
            log.warn("Turn already terminal {}, ignore event {}", current, event);
            return current;
        }
        Map<TurnEvent, TurnStatus> row = TABLE.get(current);
        TurnStatus next = row == null ? null : row.get(event);
        if (next == null) {
            throw new IllegalStateException(
                    "Invalid turn transition: " + current + " -- " + event);
        }
        return next;
    }
}
