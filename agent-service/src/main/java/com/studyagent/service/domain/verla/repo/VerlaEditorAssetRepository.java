package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaEditorAsset;

/**
 * 编辑器素材仓储接口。
 */
public interface VerlaEditorAssetRepository {

    VerlaEditorAsset save(VerlaEditorAsset asset);

    VerlaEditorAsset findByAssetId(String assetId);

    VerlaEditorAsset updateByAssetIdSelective(VerlaEditorAsset patch);
}
