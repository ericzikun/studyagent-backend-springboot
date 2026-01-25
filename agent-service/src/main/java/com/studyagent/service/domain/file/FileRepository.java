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
}

