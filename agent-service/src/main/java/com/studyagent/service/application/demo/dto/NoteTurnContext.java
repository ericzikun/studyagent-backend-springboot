package com.studyagent.service.application.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 一次「笔记回合」的运行上下文 —— 决定 {@code cmd.note.generate} payload 长什么样。
 *
 * <p>首轮（{@code firstTurn=true}）额外带素材与表单字段；后续轮只带本轮用户消息 +
 * 编辑器基线 + 会话历史。**素材字段只在首轮下发**是关键：Python 侧以
 * 「payload 是否带 objectId/inputText」判定首轮（否则后续轮会被重复当成首轮再解析一次素材）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteTurnContext {

    /** 是否为该会话的首轮（带素材，需要解析 + 首次写整篇笔记）。 */
    private boolean firstTurn;

    /** 本轮用户说了什么：首轮 = 表单 query（可空），后续轮 = 用户消息（必填）。 */
    private String message;

    /** 编辑器当前基线 md（首轮为 ""；后续轮发消息前由前端 flushSave 后带上）。 */
    private String documentContentMd;

    /** 编辑器持有的版本号，作为 AI 写入的乐观锁基线（首轮为 0）。 */
    private Long documentBaseVersion;

    /** 最近若干轮的 {@code {role, content}}，已按条数 + 字节双预算裁剪。 */
    private List<Map<String, Object>> history;

    /** 本轮输出语言覆盖（可空，缺省沿用会话偏好）。 */
    private String outputLanguage;
}
