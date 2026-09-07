package com.studyagent.api.dto.demo.aitutor;

import lombok.Data;

import java.util.List;

@Data
public class EvidenceConfirmRequest {
    private List<Long> evidenceIds;
}
