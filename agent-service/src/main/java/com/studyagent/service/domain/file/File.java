package com.studyagent.service.domain.file;

import lombok.Builder;
import lombok.Value;

/**
 * 文件领域模型
 */
@Value
@Builder
public class File {
    FileId id;
    String objectId;
    String originalFilename;
    String fileExtension;
    String contentType;
    Long fileSize;
    String fileHash;
    String storagePath;
    Integer storageType;
    String ossKey;           // OSS 对象的 Key
    String markdownContent;
    Integer markdownStatus;
    String markdownError;
}

