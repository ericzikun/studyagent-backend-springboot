package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下周期换价预告：rollout 已写入 SubscriptionSchedule、尚未到生效点。
 * account 响应用它展示「下期将按新价续费」；仅展示，不参与任何放行判定。
 */
@Data
@Builder
public class NextCyclePriceChange {
    /** 目标价最小货币单位（零小数币种即主单位）；注册表金额缺失时为 null。 */
    private Integer amountMinor;
    private String currency;
    /** 生效点 = 当前付费周期结束。 */
    private LocalDateTime effectiveAt;
    /** 目标价格版本，如 regional-20261115-v2。 */
    private String targetVersion;
}
