package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Chat With Assignment 发送消息请求。
 * <p>
 * 不带 mode：read（问答）/ write（修改）由后端模型按输入自行判定（设计 §1.5）。
 * {@code artifactUids} 可空——为空时由后端解析为本 conversation 最近一次 assignment 产物。
 */
@Data
public class AssignmentChatSendMessageRequest {

    @NotBlank
    private String message;

    /** 用户显式选中的文件；为空表示未指定（后端默认全部）。 */
    private List<String> artifactUids;
}
