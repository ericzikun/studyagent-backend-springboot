package com.studyagent.service.domain.file;

import lombok.Value;

/**
 * 文件ID值对象
 */
@Value
public class FileId {
    Long value;
    
    public static FileId of(Long value) {
        return new FileId(value);
    }
}

