package com.studyagent.service.application.verla;

import com.studyagent.service.application.verla.dto.VideoTutorSnapshotView;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 白板会话的刷新 / 重开恢复。
 * <p>
 * <b>主路径读 artifact</b>（会话级单行），事件重放只作降级 —— 见 design.md §4.2。
 * 只读：不写任何表，也不持久化前端概念（phase / activeTurn 之类由前端自己算）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTutorSnapshotService {

    private static final int MESSAGE_LIMIT = 100;
    private static final int EVENT_SCAN_LIMIT = 100;

    private final VerlaMessageRepository messageRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
    private final VideoTutorBoardStore boardStore;

    public VideoTutorSnapshotView getSnapshot(Long conversationId) {
        VideoTutorBoardStore.BoardState board = boardStore.read(conversationId);

        List<VerlaEventInbox> recent = eventInboxRepository
                .findRecentProcessedByConversation(conversationId, EVENT_SCAN_LIMIT);
        // 索引 0 是"最新一条已处理"（仓储按 id 倒序返回），而 SSE 是按 inbox.id > lastEventId 补发，
        // 所以这里给最新 id 正好表示"这之前的事件客户端都已收到"
        long resumeAfterEventId = (recent == null || recent.isEmpty()) ? 0L : recent.get(0).getId();

        List<VerlaMessage> messages = chronological(
                messageRepository.findVideoTutorByCursor(conversationId, null, MESSAGE_LIMIT));

        return new VideoTutorSnapshotView(conversationId, resumeAfterEventId,
                board.board(), board.pages(), board.pageIndex(),
                board.refSeq(), board.boardVersion(), messages, board.audio());
    }

    /**
     * 仓储按 id 倒序返回（游标分页用它翻页），快照要给的是正序 ——
     * 顺序反了的话左栏一问一答会整体倒过来，而且看起来"像是有数据"，不会报错。
     */
    private static List<VerlaMessage> chronological(List<VerlaMessage> desc) {
        if (desc == null || desc.isEmpty()) {
            return List.of();
        }
        List<VerlaMessage> copy = new ArrayList<>(desc);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }
}
