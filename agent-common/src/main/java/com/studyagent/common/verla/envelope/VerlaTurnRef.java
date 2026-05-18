package com.studyagent.common.verla.envelope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信封中的 turn 引用块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaTurnRef {

    private Long turnId;
}
