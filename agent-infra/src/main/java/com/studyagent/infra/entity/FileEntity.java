package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("files")
public class FileEntity extends BaseEntity {
    private String objectId;
    private String originalFilename;
    private String fileExtension;
    private String contentType;
    private Long fileSize;
    private String fileHash;
    private String storagePath;
    private Integer storageType;
    private String markdownContent;
    private Integer markdownStatus;
    private String markdownError;
    private Integer pageCount;
    private Integer wordCount;
    private Integer imageCount;
}

