package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.BillingEntitlementFulfillmentEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BillingEntitlementFulfillmentMapper extends BaseMapper<BillingEntitlementFulfillmentEntity> {
    @Select("""
            SELECT purchase_type AS purchaseType,
                   product_code AS productCode,
                   fulfillment_status AS state,
                   COUNT(*) AS openCount,
                   MIN(payment_accepted_at) AS oldestAcceptedAt
            FROM billing_entitlement_fulfillments
            WHERE payment_status = 'accepted'
              AND fulfillment_status IN ('pending', 'failed')
            GROUP BY purchase_type, product_code, fulfillment_status
            """)
    List<BillingFulfillmentOpenAggregate> selectOpenAggregates();
}
