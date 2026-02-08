package com.studyagent.service.domain.file;

import java.util.Optional;

/**
 * 文件Repository接口
 */
public interface FileRepository {
    Optional<File> findById(FileId id);
    Optional<File> findByObjectId(String objectId);
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

