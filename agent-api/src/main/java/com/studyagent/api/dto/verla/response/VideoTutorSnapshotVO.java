package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.verla.dto.VideoTutorSnapshotView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 白板会话刷新恢复的前端契约。
 * <p>
 * 与 assignment 的 runtime-snapshot 不同，这里不透传 {@code stateEventType} / {@code progress}
 * —— 白板没有需要后端折叠的工作流节点，前端要的只有「消息 + 板子 + 续传游标」三样。
 * 多塞字段会诱使前端把白板状态做成后端投影，而板子的唯一真相是那份 artifact。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTutorSnapshotVO {

    private String conversationId;
    /** SSE 续传游标：开 EventSource 前把它喂给 Last-Event-ID。 */
    private Long resumeAfterEventId;
    /** 板书树（与 BOARD_COMMIT / BOARD_OPS 里的结构同源）。 */
    private List<Map<String, Object>> board;
    /** 完整 A4 页计划（含当前页）；老 artifact 没有这个键。 */
    private List<Map<String, Object>> pages;
    /** 当前页在 pages 里的下标。 */
    private Integer pageIndex;
    /** 引用号计数器；前端必须用它，别自己数 —— 复用旧号会让 expand 挂错父项。 */
    private Integer refSeq;
    private Integer boardVersion;
    private List<VerlaMessageVO> messages;
    /**
     * 已落库的语音轨道：{@code "step:3"} / {@code "ask"} → {@code {url, durationMs}}。
     * 直播时音频走 ASSET_READY 帧（一次性），重新进入会话只能靠这里 —— 空对象表示这一轮没有语音
     * （开关关 / 合成失败 / 本次改动之前落的板子），前端据此不显示重听即可。
     */
    private Map<String, Object> audio;

    public static VideoTutorSnapshotVO from(VideoTutorSnapshotView view) {
        return VideoTutorSnapshotVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(view.conversationId(), true))
                .resumeAfterEventId(view.resumeAfterEventId())
                .board(view.board())
                .pages(view.pages())
                .pageIndex(view.pageIndex())
                .refSeq(view.refSeq())
                .boardVersion(view.boardVersion())
                .audio(view.audio())
                .messages(view.messages().stream().map(VerlaMessageVO::from).toList())
                .build();
    }
}
