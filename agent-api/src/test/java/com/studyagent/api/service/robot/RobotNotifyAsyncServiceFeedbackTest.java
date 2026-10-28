package com.studyagent.api.service.robot;

import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.FeedbackSubmissionEntity;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RobotNotifyAsyncServiceFeedbackTest {
    private RobotNotifyService notify;
    private FeedbackPromptSessionMapper sessions;
    private FeedbackSubmissionMapper submissions;
    private RobotNotifyAsyncService service;

    @BeforeEach
    void setUp() {
        notify = mock(RobotNotifyService.class);
        sessions = mock(FeedbackPromptSessionMapper.class);
        submissions = mock(FeedbackSubmissionMapper.class);
        service = new RobotNotifyAsyncService(notify, mock(RechargeOrderMapper.class), mock(UserMapper.class),
                mock(TaskMapper.class), mock(HumanizerTaskMapper.class), mock(TaskFileMapper.class),
                mock(FileMapper.class), sessions, submissions, mock(VerlaConversationMapper.class));
        ReflectionTestUtils.setField(service, "publicSiteUrl", "https://example.test/");
        FeedbackSubmissionEntity submission = new FeedbackSubmissionEntity();
        submission.setVote("up");
        submission.setSelectedTagCodesJson("[]");
        when(submissions.selectOne(any())).thenReturn(submission);
    }

    @Test
    void detectionPublicIdUsesDetectionLabelAndLink() {
        String publicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, 55L);
        assertNotification("detection_complete_first", publicId, "AI Detection", "ai-detection", publicId);
    }

    @Test
    void humanizerNumericIdUsesCanonicalPublicIdAndLink() {
        String publicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, 66L);
        assertNotification("humanizer_complete_first", "66", "Humanizer", "humanizer", publicId);
    }

    @Test
    void invalidConversationIdDoesNotProduceWrongLink() {
        when(sessions.selectOne(any())).thenReturn(session("humanizer_complete_first", "bad_id"));
        service.notifyFeedbackSubmitted("user_1", "fps_1", "fsub_1");
        verify(notify).dispatch(eq(RobotNotifyRouteKind.FEEDBACK), eq("feedback_fsub_1"),
                eq("notify.feedback.submitted"), contains("Humanizer 用户反馈"),
                argThat(body -> body.contains("会话 ID**: 无法解析 bad_id") && !body.contains("工作区链接")), anyMap());
    }

    private void assertNotification(String trigger, String subjectId, String label, String route, String publicId) {
        when(sessions.selectOne(any())).thenReturn(session(trigger, subjectId));
        service.notifyFeedbackSubmitted("user_1", "fps_1", "fsub_1");
        verify(notify).dispatch(eq(RobotNotifyRouteKind.FEEDBACK), eq("feedback_fsub_1"),
                eq("notify.feedback.submitted"), eq("[" + label + " 用户反馈] fsub_1"),
                argThat(body -> body.contains("功能**: " + label)
                        && body.contains("会话 ID**: " + publicId)
                        && body.contains("工作区链接**: https://example.test/" + route + "/" + publicId)
                        && body.contains("评价**: 好评")), anyMap());
    }

    private FeedbackPromptSessionEntity session(String trigger, String subjectId) {
        FeedbackPromptSessionEntity session = new FeedbackPromptSessionEntity();
        session.setSubjectType("verla_conversation");
        session.setSubjectId(subjectId);
        session.setTriggerCode(trigger);
        session.setVariant("thumb");
        return session;
    }
}
