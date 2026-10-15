package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.verla.dto.VideoTutorSnapshotView;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 快照服务：两处静默出错点必须钉住 ——
 * 消息顺序反了左栏一问一答会颠倒（有数据、不报错），
 * 续传游标给错则重连会从 0 重放整轮事件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoTutorSnapshotServiceTest {

    private static final long CID = 2001L;

    @Mock
    private VerlaMessageRepository messageRepository;
    @Mock
    private VerlaEventInboxRepository eventInboxRepository;

    private VideoTutorSnapshotService service(VerlaArtifactRepositoryStub artifactRepository) {
        VideoTutorBoardStore store = new VideoTutorBoardStore(artifactRepository, new ObjectMapper());
        return new VideoTutorSnapshotService(messageRepository, eventInboxRepository, store);
    }

    private static VerlaMessage msg(long id, String role, String text) {
        return VerlaMessage.builder()
                .id(id).conversationId(CID).role(role).textContent(text)
                .scene("VIDEO_TUTOR").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void messagesMustComeBackOldToNewEvenThoughTheRepositoryPagesNewestFirst() {
        // 仓储返回 id 倒序（游标分页靠它翻页）
        when(messageRepository.findVideoTutorByCursor(eq(CID), isNull(), anyInt()))
                .thenReturn(List.of(msg(501L, "assistant", "顶点是 (h,k)。"), msg(500L, "user", "开始讲吧")));

        VideoTutorSnapshotView view = service(new VerlaArtifactRepositoryStub(null)).getSnapshot(CID);

        assertThat(view.messages()).extracting(VerlaMessage::getTextContent)
                .containsExactly("开始讲吧", "顶点是 (h,k)。");
    }

    @Test
    void resumeCursorIsTheNewestProcessedInboxRowSoReconnectDoesNotReplayEverything() {
        when(messageRepository.findVideoTutorByCursor(eq(CID), isNull(), anyInt())).thenReturn(List.of());
        when(eventInboxRepository.findRecentProcessedByConversation(CID, 100)).thenReturn(List.of(
                VerlaEventInbox.builder().id(987L).conversationId(CID).eventSeq(12L).build(),
                VerlaEventInbox.builder().id(986L).conversationId(CID).eventSeq(11L).build()));

        VideoTutorSnapshotView view = service(new VerlaArtifactRepositoryStub(null)).getSnapshot(CID);

        // SSE 按 inbox.id > lastEventId 补发，所以这里必须是"最新一条"
        assertThat(view.resumeAfterEventId()).isEqualTo(987L);
    }

    @Test
    void noEventsAndNoBoardShouldDegradeToAnEmptyButUsableSnapshot() {
        when(messageRepository.findVideoTutorByCursor(eq(CID), isNull(), anyInt())).thenReturn(List.of());
        when(eventInboxRepository.findRecentProcessedByConversation(CID, 100)).thenReturn(List.of());

        VideoTutorSnapshotView view = service(new VerlaArtifactRepositoryStub(null)).getSnapshot(CID);

        assertThat(view.resumeAfterEventId()).isZero();
        assertThat(view.board()).isEmpty();
        assertThat(view.refSeq()).isZero();
        assertThat(view.boardVersion()).isZero();
        assertThat(view.messages()).isEmpty();
    }

    @Test
    void illustrationUrlMustSurviveTheArtifactRoundTrip() {
        // 教学配图只在板子树里留一个 OSS URL：Java 这边不做任何字段过滤、也不落音频/图片本体。
        // 哪天有人"顺手"给板子加一道白名单校验，这张图会静默消失（板子还在、图没了），所以钉住。
        when(messageRepository.findVideoTutorByCursor(eq(CID), isNull(), anyInt())).thenReturn(List.of());
        when(eventInboxRepository.findRecentProcessedByConversation(CID, 100)).thenReturn(List.of());
        String board = """
                [
                  {"op":"figure","kind":"illustration","prompt":"水墨庐山瀑布","caption":"望瀑",
                   "url":"https://studyagent.oss-us-west-1.aliyuncs.com/studyagent/video_tutor/illustration/abc.jpg",
                   "ref":"b1"},
                  {"op":"eq","latex":"y=a(x-h)^2+k","ref":"b2"}
                ]
                """;

        VideoTutorSnapshotView view = service(new VerlaArtifactRepositoryStub(artifactWith(board)))
                .getSnapshot(CID);

        assertThat(view.board()).hasSize(2);
        assertThat(view.board().get(0))
                .containsEntry("kind", "illustration")
                .containsEntry("caption", "望瀑")
                .containsEntry(
                        "url",
                        "https://studyagent.oss-us-west-1.aliyuncs.com/studyagent/video_tutor/illustration/abc.jpg");
        assertThat(view.board().get(0)).containsEntry("ref", "b1");
        assertThat(view.board().get(1)).containsEntry("latex", "y=a(x-h)^2+k");
    }

    private static com.studyagent.service.domain.verla.VerlaArtifact artifactWith(String boardJson) {
        return com.studyagent.service.domain.verla.VerlaArtifact.builder()
                .artifactUid("board_" + CID)
                .conversationId(CID)
                .kind("video_tutor_board")
                .bodyOrRef(boardJson)
                .metaJson("{\"refSeq\":2}")
                .version(3)
                .build();
    }

    /** 只实现本测试用到的那一个方法，其余留空 —— 避免把 20 个无关方法都写一遍。 */
    private static final class VerlaArtifactRepositoryStub
            implements com.studyagent.service.domain.verla.repo.VerlaArtifactRepository {

        private final com.studyagent.service.domain.verla.VerlaArtifact artifact;

        VerlaArtifactRepositoryStub(com.studyagent.service.domain.verla.VerlaArtifact artifact) {
            this.artifact = artifact;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact findByUid(String artifactUid) {
            return artifact;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact upsertByUid(
                com.studyagent.service.domain.verla.VerlaArtifact artifact) {
            return artifact;
        }
    }
}
