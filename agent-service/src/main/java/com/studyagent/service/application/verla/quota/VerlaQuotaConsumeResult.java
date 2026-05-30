package com.studyagent.service.application.verla.quota;

/**
 * V2 verla 链路扣费结果。
 *
 * @param ledgerId 扣费流水 ID；豁免时为 {@code null}
 * @param amount   扣费数量；豁免时为 {@code null}
 * @param exempt   是否豁免（admin / 白名单 / 配额开关关闭）
 */
public record VerlaQuotaConsumeResult(Long ledgerId, Long amount, boolean exempt) {

    public static VerlaQuotaConsumeResult exempted() {
        return new VerlaQuotaConsumeResult(null, null, true);
    }

    public static VerlaQuotaConsumeResult of(long ledgerId, long amount) {
        return new VerlaQuotaConsumeResult(ledgerId, amount, false);
    }
}
