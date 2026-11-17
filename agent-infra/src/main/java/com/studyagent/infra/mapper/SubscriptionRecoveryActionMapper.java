package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface SubscriptionRecoveryActionMapper extends BaseMapper<SubscriptionRecoveryActionEntity> {
    // Alias enrichment never changes dispatch ownership, status, version or frozen provider payload.
    @Update("""
            UPDATE subscription_payment_recovery_actions SET status='DISPATCHING',lease_token=#{token},lease_until=#{leaseUntil},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{version} AND action_type='VERIFY_COLLECTION'
              AND ((status='READY' AND next_run_at<=#{now}) OR status='UNKNOWN' OR (status='DISPATCHING' AND lease_until<=#{now}))
            """)
    int claimCollectionCheck(@Param("id") long id,@Param("version") int version,@Param("token") String token,
                             @Param("leaseUntil") LocalDateTime leaseUntil,@Param("now") LocalDateTime now);

    @Update("UPDATE subscription_payment_recovery_actions SET result_json=JSON_MERGE_PATCH(COALESCE(result_json,JSON_OBJECT()),CAST(#{evidence} AS JSON)) WHERE id=#{id} AND action_type IN ('CAPTURE_FAILURE','NOTIFY_INTERNAL_FAILURE')")
    int mergeFailureEvidence(@Param("id") long id,@Param("evidence") String evidence);

    @Select("SELECT id FROM subscription_payment_recovery_actions WHERE action_type='RESTORE_ADVANCE' AND status IN ('DISPATCHING','UNKNOWN','REVIEW') AND JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.expected_subscription_id'))=#{subscriptionId} ORDER BY id")
    List<Long> selectCollectionRestores(@Param("subscriptionId") String subscriptionId);
    @Select("SELECT id FROM subscription_payment_recovery_actions WHERE action_type='RESTORE_ADVANCE' AND status IN ('DISPATCHING','UNKNOWN','REVIEW') AND JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.expected_subscription_id'))=#{subscriptionId} ORDER BY id FOR UPDATE")
    List<Long> selectCollectionRestoresForUpdate(@Param("subscriptionId") String subscriptionId);

    @Select("SELECT id FROM subscription_payment_recovery_actions WHERE recovery_id=#{recoveryId} AND action_type IN ('SHIFT_RENEWAL','RELEASE_SCHEDULE','SET_CANCEL_PREFERENCE','UPDATE_METHOD') AND status IN ('READY','DISPATCHING','UNKNOWN','REVIEW') ORDER BY id FOR UPDATE")
    List<Long> selectContractMutationIdsForUpdate(@Param("recoveryId") long recoveryId);

    @Select("SELECT * FROM subscription_payment_recovery_actions WHERE action_key=#{key} LIMIT 1")
    SubscriptionRecoveryActionEntity selectByKey(@Param("key") String key);
    @Select("SELECT * FROM subscription_payment_recovery_actions WHERE id=#{id} FOR UPDATE")
    SubscriptionRecoveryActionEntity selectForUpdate(@Param("id") long id);
    @Select("SELECT * FROM subscription_payment_recovery_actions WHERE target_id=#{targetId} ORDER BY id")
    List<SubscriptionRecoveryActionEntity> selectByTarget(@Param("targetId") String targetId);
    @Select("SELECT * FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS' AND (JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.subscription_id'))=#{targetId} OR JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.checkout_id'))=#{targetId}) ORDER BY id")
    List<SubscriptionRecoveryActionEntity> selectStudyPassTargets(@Param("targetId") String targetId);

    @Select("SELECT id FROM subscription_payment_recovery_actions WHERE status='UNKNOWN' OR (status='DISPATCHING' AND lease_until<=#{now}) ORDER BY id LIMIT #{limit}")
    List<Long> selectUncertainIds(@Param("now") LocalDateTime now,@Param("limit") int limit);
    @Update("""
            UPDATE subscription_payment_recovery_actions SET first_dispatched_at=COALESCE(first_dispatched_at,#{now}),
            transport_attempts=transport_attempts+1,result_json=JSON_MERGE_PATCH(COALESCE(result_json,JSON_OBJECT()),CAST(#{evidence} AS JSON))
            WHERE id=#{id} AND status='DISPATCHING' AND lease_token=#{token} AND version=#{version}
            """)
    int markDispatched(@Param("id") long id,@Param("version") int version,@Param("token") String token,@Param("now") LocalDateTime now,@Param("evidence") String evidence);
    @Select("SELECT * FROM subscription_payment_recovery_actions WHERE recovery_id=#{recoveryId} ORDER BY id")
    List<SubscriptionRecoveryActionEntity> selectByRecovery(@Param("recoveryId") long recoveryId);
    @Select("SELECT id FROM subscription_payment_recovery_actions WHERE status='READY' AND next_run_at<=#{now} ORDER BY next_run_at,id LIMIT #{limit}")
    List<Long> selectDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
    @Update("""
            UPDATE subscription_payment_recovery_actions
            SET status='DISPATCHING', lease_token=#{token}, lease_until=#{leaseUntil}, version=version+1, updated_at=#{realNow}
            WHERE id=#{id} AND status='READY' AND next_run_at<=#{dueNow} AND version=#{version}
            """)
    int claimAt(@Param("id") long id, @Param("version") int version, @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil, @Param("dueNow") LocalDateTime dueNow,
              @Param("realNow") LocalDateTime realNow);

    default int claim(long id,int version,String token,LocalDateTime leaseUntil,LocalDateTime dueNow) {
        return claimAt(id,version,token,leaseUntil,dueNow,com.studyagent.common.datetime.DateTimeFormats.now());
    }
    @Update("""
            UPDATE subscription_payment_recovery_actions
            SET status='DISPATCHING',lease_token=#{token},lease_until=#{leaseUntil},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND action_type='SEND_EMAIL' AND status='UNKNOWN' AND version=#{version}
              AND first_dispatched_at>DATE_SUB(#{now},INTERVAL 1439 MINUTE)
              AND updated_at<=DATE_SUB(#{now},INTERVAL 60 SECOND)
              AND lease_token IS NULL AND provider_idempotency_key IS NOT NULL
            """)
    int claimUnknownMail(@Param("id") long id,@Param("version") int version,@Param("token") String token,
                         @Param("leaseUntil") LocalDateTime leaseUntil,@Param("now") LocalDateTime now);

    @Update("""
            UPDATE subscription_payment_recovery_actions
            SET status=#{status}, result_json=JSON_MERGE_PATCH(COALESCE(result_json,JSON_OBJECT()),CAST(#{result} AS JSON)),
            provider_request_id=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{result},'$.provider_request_id')),'null'),provider_request_id),
            payment_intent_id=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{result},'$.payment_intent_id')),'null'),payment_intent_id),
            charge_id=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{result},'$.charge_id')),'null'),charge_id),
            provider_message_id=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{result},'$.provider_message_id')),'null'),provider_message_id),
            last_error=NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{result},'$.error')),'null'),
            lease_token=NULL, lease_until=NULL, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status='DISPATCHING' AND lease_token=#{token} AND version=#{version}
            """)
    int complete(@Param("id") long id, @Param("version") int version, @Param("token") String token,
                 @Param("status") String status, @Param("result") String result, @Param("now") LocalDateTime now);
}
