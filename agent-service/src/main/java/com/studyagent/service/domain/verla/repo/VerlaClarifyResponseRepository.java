package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaClarifyResponse;

import java.util.List;

/**
 * Verla 澄清问卷响应仓储接口（V2）。
 * <p>
 * 详见 docs/V2/5.1 §3 / §4。
 */
public interface VerlaClarifyResponseRepository {

    VerlaClarifyResponse save(VerlaClarifyResponse response);

    VerlaClarifyResponse findById(Long id);

    VerlaClarifyResponse findByResponseUid(String responseUid);

    /** 单个 form 的所有历史响应（一般只 1 条；若 reopen 可有多条） */
    List<VerlaClarifyResponse> findByFormId(String formId);
}
