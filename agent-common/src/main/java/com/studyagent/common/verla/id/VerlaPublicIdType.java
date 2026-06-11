package com.studyagent.common.verla.id;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * V2 对外 public id 类型前缀。
 * <p>
 * 格式：{@code {prefix}_{sqids_encoded_internal_id}}，例如 {@code vc_FxnXM1kBN}。
 */
@Getter
@RequiredArgsConstructor
public enum VerlaPublicIdType {

    CONVERSATION("vc"),
    TURN("vt"),
    SESSION("vs"),
    MESSAGE("vm"),
    /** verla_artifacts 表内部主键（与 artifactUid 业务 ID 区分） */
    ARTIFACT("va"),
    /** V1 legacy task，无前缀 Sqids 短码（与 {@code TaskIdEncoder} 一致） */
    LEGACY_TASK(null);

    private final String prefix;

    public boolean hasPrefix() {
        return prefix != null;
    }
}
