package com.studyagent.api.dto.demo.note;

import lombok.Data;

/**
 * 一次「笔记回合」的请求体。
 * <p><b>首轮</b>：带 {@code objectId}（文件，必填）与可选 {@code message}（= 表单 query），
 * 以及课程名/学习目标/语言；<b>后续轮</b>：只带 {@code message} + 编辑器基线
 * （{@code documentContentMd} / {@code documentBaseVersion}）。
 */
@Data
public class NoteGenerateRequest {

    /** {@code file} 或 {@code text}；缺省时按 objectId / text 是否存在推断。 */
    private String sourceType;

    /** sourceType=file 时的附件 objectId（由 /v1/verla/v2/uploads 链路产出）。 */
    private String objectId;

    /** sourceType=text 时粘贴的原始文本。 */
    private String text;

    /**
     * 本轮用户说了什么。
     * <p>首轮 = 上传表单里的 query（可选，为空则直接按素材建笔记）；后续轮必填。
     */
    private String message;

    /**
     * 编辑器当前基线 md（后续轮发消息前由前端 flushSave 后带上）。
     * <p>与 ai-tutor 同「全量快照」语义：AI 在服务端合并后返回整篇。
     */
    private String documentContentMd;

    /** 编辑器持有的版本号，作为 AI 写入的乐观锁基线（首轮为 0）。 */
    private Long documentBaseVersion;

    /** 文件展示名（可选，仅用于来源标签与初始标题）。 */
    private String filename;

    /** 输出语言（可选，如 zh-CN / chinese）；缺省沿用会话偏好。 */
    private String outputLanguage;

    /** 上传表单：课程名称（可选）。 */
    private String courseName;

    /**
     * 上传表单：学习目标枚举码。
     * <p>exam_prep / quiz_review / homework / understand_lesson /
     * take_notes / generate_questions / flashcards / other；缺省 take_notes。
     */
    private String learningGoal;

    /**
     * 本轮显式引用的历史笔记（demo_note_conversation.id）。
     * <p>服务端只认当前用户自己的会话，并做条数 / 字符双预算裁剪后才下发给 Python。
     */
    private java.util.List<Long> referencedNoteIds;
}
