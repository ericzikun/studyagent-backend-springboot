package com.studyagent.service.domain.file;

import java.util.Optional;
import java.util.List;
import java.util.Map;

/**
 * 文件Repository接口
 */
public interface FileRepository {
    Optional<File> findById(FileId id);
    Optional<File> findByObjectId(String objectId);

    /** 批量按 objectId 查询，避免任务提交时 N+1。 */
    Map<String, File> findByObjectIds(List<String> objectIds);
    File save(File file);
    void delete(FileId id);
    
    /**
     * 更新文件的 OSS Key
     * @param objectId 文件对象ID
     * @param ossKey OSS 对象的 Key
     * @return 是否更新成功
     */
    boolean updateOssKey(String objectId, String ossKey);
}

