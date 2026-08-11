package com.studyagent.infra.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentDispatchMetricsSnapshot {

    private Integer pending;
    private Long oldestAgeSeconds;
    private Integer queued;
    private Integer dispatching;
    private Integer running;
}
