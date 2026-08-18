package com.studyagent.service.application.emaillead;

/**
 * 匿名邮箱写入的 Redis 防护边界。
 */
public interface PublicEmailLeadWriteGuard {

    /** 校验并登记一次单 IP 短窗口请求。 */
    void checkIpRateLimit(String clientIp);

    /** 为确认尚不存在的邮箱预占一个“当日新增邮箱”名额。 */
    void reserveDailyNew();

    /**
     * 当数据库确认本次不是新增写入时归还预占名额。
     */
    void releaseDailyReservation();
}
