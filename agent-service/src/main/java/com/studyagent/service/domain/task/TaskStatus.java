package com.studyagent.service.domain.task;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    DRAFT(0, "草稿"),
    PENDING(1, "待执行"),
    IN_PROGRESS(2, "执行中"),
    COMPLETED(3, "已完成"),
    FAILED(4, "失败"),
    CANCELLED(5, "已取消");
    
    private final Integer code;
    private final String desc;
    
    TaskStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public Integer getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static TaskStatus fromCode(Integer code) {
        if (code == null) {
            return DRAFT;
        }
        for (TaskStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return DRAFT;
    }
}

