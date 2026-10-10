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
    /** 引用号计数器；前端必须用它，别自己数 —— 复用旧号会让 expand 挂错父项。 */
    private Integer refSeq;
    private Integer boardVersion;
    private List<VerlaMessageVO> messages;

    public static VideoTutorSnapshotVO from(VideoTutorSnapshotView view) {
        return VideoTutorSnapshotVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(view.conversationId(), true))
                .resumeAfterEventId(view.resumeAfterEventId())
                .board(view.board())
                .refSeq(view.refSeq())
                .boardVersion(view.boardVersion())
                .messages(view.messages().stream().map(VerlaMessageVO::from).toList())
                .build();
    }
}
