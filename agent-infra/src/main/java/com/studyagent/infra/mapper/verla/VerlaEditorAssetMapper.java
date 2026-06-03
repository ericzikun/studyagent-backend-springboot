package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaEditorAssetEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 编辑器素材 Mapper。
 */
public interface VerlaEditorAssetMapper extends BaseMapper<VerlaEditorAssetEntity> {

    @Select("SELECT * FROM verla_editor_assets WHERE asset_id = #{assetId} LIMIT 1")
    VerlaEditorAssetEntity selectByAssetId(@Param("assetId") String assetId);
}
