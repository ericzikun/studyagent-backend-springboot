package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.handler.VideoTutorEventHandler;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.TurnStateMachine;
import com.studyagent.service.domain.verla.state.TurnStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-3：白板 turn 的派发内容。
 * <p>
 * 这套断言真正在守的是<b>"Python 无状态"</b>这个不变量：每轮 payload 必须自带
 * {@code message / topic / history / board / refSeq / boardVersion / outputLanguage} 七样。
 * 少任何一样，Python 就只能靠进程内缓存才能跑通 —— 那会直接违反架构不变量，
 * 而且症状是"单机能用、多副本失忆"，在测试环境很难暴露。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoTutorTurnDispatchTest {

    private static final String USER_ID = "u_video_1";
    private static final long CONVERSATION_ID = 2001L;
    private static final String BOARD_UID = "board_" + CONVERSATION_ID;

    @Mock
    private VerlaConversationService conversationService;
    @Mock
    private VerlaConversationRepository conversationRepository;
    @Mock
    private VerlaTurnRepository turnRepository;
    @Mock
    private VerlaSessionRepository sessionRepository;
    @Mock
    private VerlaMessageRepository messageRepository;
    @Mock
    private VerlaAttachmentRepository attachmentRepository;
    @Mock
    private VerlaArtifactRepository artifactRepository;
    @Mock
    private MqOutboxService mqOutboxService;
    @Mock
    private VerlaQuotaService quotaService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private AnalyticsService analyticsService;

    private VerlaTurnOrchestrator orchestrator;
    private final List<VerlaSession> savedSessions = new ArrayList<>();
    private final List<VerlaTurn> savedTurns = new ArrayList<>();
    private final List<VerlaMessage> savedMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(conversationService.loadWritable(USER_ID, CONVERSATION_ID)).thenReturn(
                VerlaConversation.builder()
                        .id(CONVERSATION_ID).userId(USER_ID).title("二次函数的顶点式")
                        .version(7L).createdAt(LocalDateTime.now()).build());
        when(conversationRepository.touchOnNewTurnAndGetVersion(eq(CONVERSATION_ID), any()))
                .thenReturn(8L);

        // save() 必须回填自增 id：correlationId、payload 里排除"本轮这条"都依赖它
        AtomicLong ids = new AtomicLong(500);
        when(messageRepository.save(any(VerlaMessage.class))).thenAnswer(inv -> {
            VerlaMessage m = inv.getArgument(0);
            m.setId(ids.incrementAndGet());
            savedMessages.add(m);
            return m;
        });
        when(turnRepository.save(any(VerlaTurn.class))).thenAnswer(inv -> {
            VerlaTurn t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(ids.incrementAndGet());
                savedTurns.add(t);   // save 会被调多次（插入 + 状态回填更新），只在首次记账
            }
            return t;
        });
        when(sessionRepository.save(any(VerlaSession.class))).thenAnswer(inv -> {
            VerlaSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
                savedSessions.add(s);
            }
            return s;
        });
        when(messageRepository.findVideoTutorByCursor(eq(CONVERSATION_ID), any(), anyInt()))
                .thenReturn(List.of());
        // 并发守卫读"最近几个 turn"，这里用真实写进去的那些，让守卫真的跨轮生效
        when(turnRepository.findRecentByConversation(anyLong(), anyInt()))
                .thenAnswer(inv -> new ArrayList<>(savedTurns));

        orchestrator = new VerlaTurnOrchestrator(
                conversationService, conversationRepository, turnRepository, sessionRepository,
                messageRepository, attachmentRepository, artifactRepository, null,
                new TurnStateMachine(), new SessionStateMachine(), mqOutboxService,
                new ObjectMapper(), quotaService, entitlementService, null,
                event -> { }, analyticsService);
    }

    private VerlaCommandEnvelope dispatchedEnvelope() {
        ArgumentCaptor<VerlaCommandEnvelope> captor = ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(captor.capture(), any(), any());
        return captor.getValue();
    }

    private Map<String, Object> dispatchedPayload() {
        return dispatchedEnvelope().getPayload();
    }

    private static VerlaMessage msg(long id, String role, String content) {
        return VerlaMessage.builder()
                .id(id).conversationId(CONVERSATION_ID).role(role).textContent(content)
                .scene(VideoTutorEventHandler.SCENE_VIDEO_TUTOR)
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void firstTurn_payloadCarriesEveryFieldPythonNeedsToStayStateless() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "k 不存在怎么办？", null);

        Map<String, Object> payload = dispatchedPayload();
        assertThat(payload).containsOnlyKeys(
                "message", "topic", "history", "board", "refSeq", "boardVersion", "outputLanguage");
        assertThat(payload.get("message")).isEqualTo("k 不存在怎么办？");
        assertThat(payload.get("topic")).isEqualTo("二次函数的顶点式");
        assertThat(payload).containsEntry("board", List.of())
                .containsEntry("refSeq", 0)
                .containsEntry("boardVersion", 0);

        // 刻意不给 contextRef：白板对首帧延迟敏感，回拉会在第一个 step 之前多插一次内部 RPC。
        // 这条断言的意义是 —— 哪天有人"顺手对齐 assignment chat"把它加回来，测试会提醒这是有意分歧。
        assertThat(payload).doesNotContainKey("contextRef");
    }

    @Test
    void commandCodeMatchesThePythonDispatchBranchVerbatim() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "开始", null);

        // envelope.action 是字符串；Python 的 _run_with_context 按值比分支，写错就是静默不执行
        assertThat(dispatchedEnvelope().getAction())
                .isEqualTo(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT.getCode())
                .isEqualTo("cmd.video_tutor.chat");
    }

    @Test
    void secondTurn_continuesBoardAndReferenceCounterFromTheArtifactRow() throws Exception {
        List<Map<String, Object>> board = List.of(
                Map.of("ref", "b6", "op", "eq", "latex", "y=a(x-h)^2+k"),
                Map.of("ref", "b7", "op", "line", "text", "a 管开口"));
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(VerlaArtifact.builder()
                .artifactUid(BOARD_UID).kind(VideoTutorEventHandler.BOARD_ARTIFACT_KIND)
                .version(3)
                .bodyOrRef(new ObjectMapper().writeValueAsString(board))
                .metaJson("{\"refSeq\":7,\"pacing\":{\"breaks\":0}}")
                .build());

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "那 h 呢？", null);

        Map<String, Object> payload = dispatchedPayload();
        assertThat((List<?>) payload.get("board")).hasSize(2);
        // 引用号必须续上而不是从 0 重来：复用旧号会让陈旧的 of:"b3" 指向擦黑板后新写的无关条目
        assertThat(payload.get("refSeq")).isEqualTo(7);
        assertThat(payload.get("boardVersion")).isEqualTo(3);
    }

    @Test
    void history_isOldToNew_andExcludesTheMessageBeingSentThisTurn() {
        // 游标查询返回 id 倒序（新→旧），并且已经把本轮这条 user 消息落库了
        when(messageRepository.findVideoTutorByCursor(eq(CONVERSATION_ID), any(), anyInt()))
                .thenAnswer(inv -> {
                    long currentId = savedMessages.get(savedMessages.size() - 1).getId();
                    return List.of(
                            msg(currentId, "user", "k 不存在怎么办？"),
                            msg(currentId - 1, "assistant", "顶点是 (h,k)。"),
                            msg(currentId - 2, "user", "开始讲吧"));
                });

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "再说一遍", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) dispatchedPayload().get("history");
        assertThat(history).extracting(m -> String.valueOf(m.get("role")))
                .containsExactly("user", "assistant");
        assertThat(history).extracting(m -> String.valueOf(m.get("content")))
                .containsExactly("开始讲吧", "顶点是 (h,k)。");
    }

    @Test
    void historyByteBudget_skipsTheOversizedItemInsteadOfTruncatingTheRest() {
        String huge = "x".repeat(30 * 1024);
        when(messageRepository.findVideoTutorByCursor(eq(CONVERSATION_ID), any(), anyInt()))
                .thenReturn(List.of(
                        msg(903L, "user", "最新的小问题"),
                        msg(902L, "assistant", huge),
                        msg(901L, "user", "最早的")));

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "继续", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) dispatchedPayload().get("history");
        // 关键：超预算那条被跳过，但更新的轮次仍然带上 —— break 会把最近的对话整段丢掉
        assertThat(history).extracting(m -> String.valueOf(m.get("content")))
                .containsExactly("最早的", "最新的小问题");
        // 多取一条，正好容纳"本轮刚插入的那条随后要被排除"的余量
        verify(messageRepository).findVideoTutorByCursor(eq(CONVERSATION_ID), any(), eq(41));
    }

    @Test
    void emptyFirstMessageIsAllowedAndReachesPythonAsBlank() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        SendMessageResult result = orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "   ", null);

        assertThat(dispatchedPayload().get("message")).isEqualTo("");
        assertThat(result.getAgentSessionId()).isNotNull();
    }

    @Test
    void runningPreviousTurnIsRejectedSoBoardsCannotSilentlyOverwriteEachOther() {
        // 白板队列不吃 SAC ⇒ 两轮真可能并发；每轮都基于下发时刻的 board 快照 reduce，
        // 后写完的覆盖前一轮，症状只是"少了半轮板书"且不报错 —— 所以在入口硬挡
        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第一轮", null);

        assertThatThrownBy(() -> orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "抢跑的第二轮", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    // 信息走 data 不走 message：BusinessException(ApiCode, Object...) 那个重载会把
                    // 自定义文案交给 apiCode.formatEn()，最终只剩 "Illegal state"
                    assertThat(be.getData()).isInstanceOfSatisfying(Map.class,
                            data -> assertThat(data)
                                    .containsEntry("reason", "VIDEO_TUTOR_TURN_RUNNING")
                                    .containsKey("runningTurnId"));
                });
    }

    /**
     * 顺序是这个守卫唯一的有效前提：<b>先锁会话行，再读"有没有未终态 turn"</b>。
     * 反过来写的话锁形同虚设 —— 两个并发请求会同时读到"没有"，双双放行，
     * 后写完的一轮静默覆盖前一轮的板书，且不报错。
     */
    @Test
    void conversationRowIsLockedBeforeTheGuardReadsRecentTurns() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第一轮", null);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(conversationRepository, turnRepository);
        inOrder.verify(conversationRepository).findByIdForUpdate(CONVERSATION_ID);
        inOrder.verify(turnRepository).findRecentByConversation(CONVERSATION_ID, 5);
    }

    @Test
    void nextTurnIsAllowedOnceThePreviousOneIsTerminal() {
        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第一轮", null);
        savedTurns.forEach(t -> t.setStatus(TurnStatus.COMPLETED.name()));

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第二轮", null);

        verify(mqOutboxService, org.mockito.Mockito.times(2)).createVerlaCommand(
                any(), any(), eq(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT.getCode()));
    }

    @Test
    void everyTurnSpawnsItsOwnSessionWithABackfilledCorrelationId() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第一轮", null);
        savedTurns.forEach(t -> t.setStatus(TurnStatus.COMPLETED.name()));
        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第二轮", null);

        assertThat(savedSessions).hasSize(2);
        assertThat(savedSessions.get(0).getId()).isNotEqualTo(savedSessions.get(1).getId());
        for (VerlaSession s : savedSessions) {
            assertThat(s.getCorrelationId()).isNotEqualTo("placeholder");
            assertThat(s.getKind()).isEqualTo(VerlaSessionKind.VIDEO_TUTOR.name());
            // v1 不接额度：feature_code 留 NULL（列可空），不动 FeatureCode 枚举
            assertThat(s.getFeatureCode()).isNull();
        }
    }

    @Test
    void cancelRejectsAnotherDomainsSessionAndAlreadyTerminalOnes() {
        when(sessionRepository.findByIdForUpdate(77L)).thenReturn(VerlaSession.builder()
                .id(77L).conversationId(CONVERSATION_ID).kind(VerlaSessionKind.ASSIGNMENT.name())
                .status("RUNNING").turnId(1L).build());
        assertThatThrownBy(() -> orchestrator.cancelVideoTutorTurn(USER_ID, CONVERSATION_ID, 77L))
                .isInstanceOf(BusinessException.class);

        when(sessionRepository.findByIdForUpdate(78L)).thenReturn(VerlaSession.builder()
                .id(78L).conversationId(CONVERSATION_ID).kind(VerlaSessionKind.VIDEO_TUTOR.name())
                .status("SUCCEEDED").turnId(2L).build());
        assertThatThrownBy(() -> orchestrator.cancelVideoTutorTurn(USER_ID, CONVERSATION_ID, 78L))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> orchestrator.cancelVideoTutorTurn(USER_ID, CONVERSATION_ID, null))
                .isInstanceOf(BusinessException.class);
        verify(mqOutboxService, never()).createVerlaCommand(any(), any(), any());
    }

    /** 一个 scene 串不能有第二个真相：turn 写入与 handler 幂等查重用的是同一个常量。 */
    @Test
    void userMessageAndHandlerShareOneSceneConstant() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "第一轮", null);

        assertThat(savedMessages.get(0).getRole()).isEqualTo("user");
        assertThat(savedMessages.get(0).getMetaJson()).contains(VideoTutorEventHandler.SCENE_VIDEO_TUTOR);
    }

    @Test
    void clientOutputLanguageIsPersistedThroughTheExistingSetter() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);
        // 语言取值链路：前端传 locale → setOutputLanguage 归一化落 workspace_json →
        // resolveOutputLanguage 读它 → 进 payload。这里断言两端：传参被落库、落库结果进 payload。
        // （"读 workspace 拿到值"是既有 service 的行为，由它自己的测试覆盖。）
        when(conversationService.resolveOutputLanguage(any())).thenReturn("chinese");

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "开始", "zh-CN");

        verify(conversationService).setOutputLanguage(any(), eq("zh-CN"));
        assertThat(dispatchedPayload().get("outputLanguage")).isEqualTo("chinese");
    }

    @Test
    void blankOutputLanguageMustNotOverwriteAnExistingPreference() {
        when(artifactRepository.findByUid(BOARD_UID)).thenReturn(null);
        when(conversationService.resolveOutputLanguage(any())).thenReturn("english");

        orchestrator.startVideoTutorTurn(USER_ID, CONVERSATION_ID, "开始", "  ");

        verify(conversationService, never()).setOutputLanguage(any(), any());
        assertThat(dispatchedPayload().get("outputLanguage")).isEqualTo("english");
    }
}
