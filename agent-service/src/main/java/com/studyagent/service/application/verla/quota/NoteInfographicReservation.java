package com.studyagent.service.application.verla.quota;

/**
 * 知识图额度预占结果。
 * <p>与普通扣费不同：余额不足时**不抛异常**，让笔记正文/导图/播客/测验继续生成，
 * 只把知识图 tab 置为 QUOTA_EXHAUSTED。
 */
public record NoteInfographicReservation(Status status, Long ledgerId, long amount) {

    public enum Status {
        /** admin / Quota VIP / env 白名单 / 总开关关闭 */
        EXEMPT,
        /** 已扣 1 次并绑定到 session */
        RESERVED,
        /** 余额不足，未扣费 */
        INSUFFICIENT
    }

    public static NoteInfographicReservation exempt() {
        return new NoteInfographicReservation(Status.EXEMPT, null, 0L);
    }

    public static NoteInfographicReservation reserved(long ledgerId) {
        return new NoteInfographicReservation(Status.RESERVED, ledgerId, 1L);
    }

    public static NoteInfographicReservation insufficient() {
        return new NoteInfographicReservation(Status.INSUFFICIENT, null, 0L);
    }

    public boolean allowed() {
        return status != Status.INSUFFICIENT;
    }
}
