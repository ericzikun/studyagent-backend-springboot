package com.studyagent.api.dto.demo.note;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 笔记会话响应体。
 * <p>{@code id} 是 demo 自己的主键（前端 REST 路径继续用它），
 * {@code verlaConversationId} 是主线 public id（{@code vc_xxx}），用于建立 SSE 事件通道。
 * 刷新页面时 {@code noteMd} 就是已生成的笔记正文（未生成为 null），
 * {@code messages} 是对话栏的消息列表（旧→新）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteConversationVO {

    private Long id;
    private String verlaConversationId;
    private String title;
    private String sourceType;
    private String attachmentObjectId;
    /**
     * 上传附件的原始文件名（标题会被 AI 起的会话名覆盖，所以单独回传）。
     * <p>左栏首页的「提交摘要」气泡靠它渲染带类型图标的文件 chip。
     */
    private String attachmentFilename;
    /**
     * sourceType=text 时粘贴的原始文本。
     * <p>刻意不截断：前端按行数折叠展示，截断会破坏「看全文」。
     */
    private String inputText;
    /** 表单：课程名称。 */
    private String courseName;
    /** 表单：学习目标枚举码。 */
    private String learningGoal;
    /** 表单：笔记输出语言码。 */
    private String outputLanguage;
    private String noteMd;
    /**
     * 笔记版本号：前端保存时回传作为乐观锁基线（不匹配则 400，需重新拉快照）。
     */
    private Long noteVersion;
    private String status;
    /**
     * 对话栏消息（旧→新）。快照端点返回；派发/保存等端点不查消息表，故为 null。
     */
    private List<NoteMessageVO> messages;
    /**
     * 待用户接受/忽略的章节级改动提案；无待审为 null。
     * <p>落库而不是只放前端内存，刷新页面仍能继续审阅。
     */
    private Map<String, Object> pendingProposal;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static NoteConversationVO from(NoteConversation c) {
        return from(c, null, null);
    }

    public static NoteConversationVO from(NoteConversation c, List<NoteMessage> messages) {
        return from(c, messages, null);
    }

    public static NoteConversationVO from(NoteConversation c, List<NoteMessage> messages,
                                         Map<String, Object> pendingProposal) {
        if (c == null) {
            return null;
        }
        return NoteConversationVO.builder()
                .id(c.getId())
                .verlaConversationId(VerlaPublicIdVoSupport.conversation(c.getVerlaConversationId(), true))
                .title(c.getTitle())
                .sourceType(c.getSourceType())
                .attachmentObjectId(c.getAttachmentObjectId())
                .attachmentFilename(c.getAttachmentFilename())
                .inputText(c.getInputText())
                .courseName(c.getCourseName())
                .learningGoal(c.getLearningGoal())
                .outputLanguage(c.getOutputLanguage())
                .noteMd(c.getNoteMd())
                .noteVersion(c.getNoteVersion())
                .status(c.getStatus())
                .messages(messages == null ? null : messages.stream().map(NoteMessageVO::from).toList())
                .pendingProposal(pendingProposal)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
