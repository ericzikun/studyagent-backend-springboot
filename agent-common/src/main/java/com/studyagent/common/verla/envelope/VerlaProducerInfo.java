package com.studyagent.common.verla.envelope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命令/事件的发起方信息（用于排障）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaProducerInfo {

    /**
     * 服务名：java-agent-service / python-verla-agent
     */
    private String service;

    /**
     * 实例 ID：hostname / k8s pod name
     */
    private String instanceId;
}
