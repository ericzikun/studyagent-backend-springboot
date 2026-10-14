package com.studyagent.service.application.demo.dto;

import com.studyagent.service.domain.demo.note.NoteConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 一轮笔记回合的「已准备」结果：会话行已更新、用户消息已落库、history 已裁好。
 * <p>{@code history} 必须在**本轮用户消息落库之前**取，否则会把当前消息重复塞进历史。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteTurnPreparation {

    private NoteConversation conversation;

    /** 已按条数 + 字节双预算裁剪的历史（旧→新），首轮为空列表。 */
    private List<Map<String, Object>> history;
}
