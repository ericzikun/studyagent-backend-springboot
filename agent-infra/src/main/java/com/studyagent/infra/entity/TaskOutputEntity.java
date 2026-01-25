package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务输出表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_outputs")
public class TaskOutputEntity extends BaseEntity {
    @TableField("task_id")
    private Long taskId;
    
    private String title;
    private String description;
    
    @TableField("file_path")
    private String filePath;
    
    @TableField("download_url")
    private String downloadUrl;
    
    @TableField("size_desc")
    private String sizeDesc;
    
    @TableField("page_size")
    private Integer pageSize;
    
    private Integer format;
    
    @TableField("output_type")
    private Integer outputType;
    
    @TableField("content_text")
    private String contentText;
    
    @TableField("log_text")
    private String logText;
    
    @TableField("content_json")
    private String contentJson;
}

