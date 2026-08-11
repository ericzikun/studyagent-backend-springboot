package com.studyagent.service.application.verla;

import com.studyagent.service.application.verla.cache.ConversationMessagesPageCacheValue;
import com.studyagent.service.application.verla.cache.ConversationSummaryCacheValue;
import com.studyagent.service.application.verla.cache.SessionMetaCacheValue;
import com.studyagent.service.application.verla.cache.TurnMetaCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import com.studyagent.service.application.verla.dto.VerlaSessionContextQueryOptions;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaContextQueryServiceRedisTest {

    @Test
    void cache_metrics_use_fixed_low_cardinality_contract() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        VerlaContextQueryService service = new VerlaContextQueryService(
                mock(VerlaConversationRepository.class),
                mock(VerlaTurnRepository.class),
                mock(VerlaSessionRepository.class),
                mock(VerlaMessageRepository.class),
                mock(VerlaArtifactRepository.class),
                mock(VerlaToolCallRepository.class),
                meterRegistry,
                properties,
                Optional.empty());

        service.init();

        assertThat(meterRegistry.find("verla.context.cache.hit.total").counters())
                .hasSize(3)
                .allMatch(counter -> counter.getId().getType() == Meter.Type.COUNTER)
                .extracting(counter -> counter.getId().getTag("layer"))
                .containsExactlyInAnyOrder("l1", "redis", "db");

        assertCounterHasNoTags(meterRegistry, "verla.context.cache.db.fallback.total");
        assertCounterHasNoTags(meterRegistry, "verla.cache.redis.read.error.total");
        assertCounterHasNoTags(meterRegistry, "verla.cache.redis.write.error.total");
        assertCounterHasNoTags(meterRegistry, "verla.cache.redis.circuit.skip.total");
    }

    private static void assertCounterHasNoTags(SimpleMeterRegistry meterRegistry, String name) {
        Meter meter = meterRegistry.get(name).meter();
        assertThat(meter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(meter.getId().getTags()).isEmpty();
    }

    @Test
    void loadSessionAndLoadTurn_shouldUseRedisBeforeDb() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("RUNNING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("RUNNING_AGENT")
                .resolvedIntent("assignment.run")
                .activeSessionId(301L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("user")
                .textContent("帮我解释一下")
                .createdAt(LocalDateTime.now())
                .build();

        when(sessionRepository.findById(301L))
                .thenThrow(new AssertionError("session should come from redis"));
        when(turnRepository.findById(201L))
                .thenThrow(new AssertionError("turn should come from redis"));
        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listBySession(301L, 50)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        when(redisContextCache.getSessionMeta(keyFactory.sessMetaKey(301L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<SessionMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(301L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new SessionMetaCacheValue(session))
                        .build()));
        when(redisContextCache.getTurnMeta(keyFactory.turnMetaKey(201L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<TurnMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(201L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new TurnMetaCacheValue(turn))
                        .build()));
        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build()));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getSessionContext(301L, 7L, null);

        assertThat(view.getSession().getId()).isEqualTo(301L);
        assertThat(view.getTurn().getId()).isEqualTo(201L);
        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        verify(redisContextCache).getSessionMeta(keyFactory.sessMetaKey(301L));
        verify(redisContextCache).getTurnMeta(keyFactory.turnMetaKey(201L));
        verify(sessionRepository, never()).findById(301L);
        verify(turnRepository, never()).findById(201L);
    }

    @Test
    void sessionContext_shouldKeepAggregatingSessionTurnConversationSeparately() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("RUNNING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("RUNNING_AGENT")
                .activeSessionId(301L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaArtifact artifact = VerlaArtifact.builder()
                .id(501L)
                .conversationId(101L)
                .artifactUid("art_1")
                .build();
        VerlaToolCall toolCall = VerlaToolCall.builder()
                .toolCallId("call_1")
                .conversationId(101L)
                .sessionId(301L)
                .toolName("solve")
                .summary("summarized")
                .visibility("USER_VISIBLE")
                .status("SUCCEEDED")
                .build();

        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of(artifact));
        when(toolCallRepository.listBySession(301L, 50)).thenReturn(List.of(toolCall));
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of(toolCall));

        when(redisContextCache.getSessionMeta(keyFactory.sessMetaKey(301L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<SessionMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(301L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new SessionMetaCacheValue(session))
                        .build()));
        when(redisContextCache.getTurnMeta(keyFactory.turnMetaKey(201L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<TurnMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(201L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new TurnMetaCacheValue(turn))
                        .build()));
        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build()));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getSessionContext(
                301L,
                7L,
                null,
                VerlaSessionContextQueryOptions.builder()
                        .includeArtifacts(true)
                        .includeTrace(true)
                        .build());

        assertThat(view.getSession().getId()).isEqualTo(301L);
        assertThat(view.getTurn().getId()).isEqualTo(201L);
        assertThat(view.getConversation().getId()).isEqualTo(101L);
        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9001L);
        assertThat(view.getArtifacts()).extracting(VerlaArtifact::getId).containsExactly(501L);
        assertThat(view.getRecentToolCalls()).extracting(VerlaToolCall::getToolCallId).containsExactly("call_1");
        verify(redisContextCache).getSessionMeta(keyFactory.sessMetaKey(301L));
        verify(redisContextCache).getTurnMeta(keyFactory.turnMetaKey(201L));
        verify(redisContextCache).getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20));
        verify(artifactRepository).findByConversation(101L);
        verify(toolCallRepository).listBySession(301L, 50);
    }

    @Test
    void sessionContext_shouldStillLoadUpstreamSessionsFromDb() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("RUNNING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaSession upstream = VerlaSession.builder()
                .id(302L)
                .conversationId(101L)
                .turnId(201L)
                .kind("AGENT")
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("RUNNING_AGENT")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();

        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of(upstream));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        when(redisContextCache.getSessionMeta(keyFactory.sessMetaKey(301L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<SessionMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(301L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new SessionMetaCacheValue(session))
                        .build()));
        when(redisContextCache.getTurnMeta(keyFactory.turnMetaKey(201L))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<TurnMetaCacheValue>builder()
                        .schemaVersion(1)
                        .version(201L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new TurnMetaCacheValue(turn))
                        .build()));
        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build()));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getSessionContext(301L, 7L, null);

        assertThat(view.getUpstreamSessions()).extracting(VerlaSession::getId).containsExactly(302L);
        verify(sessionRepository).findCompletedSiblings(201L, 301L);
        verify(redisContextCache, never()).delete(anyString());
    }

    @Test
    void getSessionContext_shouldUseRedisSummaryWhenConvVersionProvided() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("user")
                .textContent("帮我解释一下")
                .createdAt(LocalDateTime.now())
                .build();

        when(sessionRepository.findById(301L)).thenReturn(session);
        when(turnRepository.findById(201L)).thenReturn(turn);
        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listBySession(301L, 50)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        String summaryKey = keyFactory.convSummaryKey(101L, 7L, 20);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> envelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build();
        when(redisContextCache.getConversationSummary(summaryKey)).thenReturn(Optional.of(envelope));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getSessionContext(301L, 7L, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9001L);
        assertThat(view.getCacheHitLayer()).isEqualTo("redis");
        verify(redisContextCache).getConversationSummary(summaryKey);
        verify(conversationRepository, never()).findById(101L);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
    }

    @Test
    void getConversationContext_shouldUseRedisMessagesPageWhenBeforeProvided() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn latestTurn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage historyMessage = VerlaMessage.builder()
                .id(8999L)
                .conversationId(101L)
                .role("assistant")
                .textContent("上一条说明")
                .createdAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findById(101L)).thenReturn(conversation);
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        String pageKey = keyFactory.convMessagesKey(101L, 7L, 9001L, 20);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationMessagesPageCacheValue> envelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationMessagesPageCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationMessagesPageCacheValue(List.of(historyMessage)))
                        .build();
        when(redisContextCache.getConversationMessagesPage(pageKey)).thenReturn(Optional.of(envelope));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getConversationContext(101L, 7L, 9001L, null);

        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(8999L);
        assertThat(view.getCacheHitLayer()).isEqualTo("redis");
        verify(redisContextCache).getConversationMessagesPage(pageKey);
        verify(messageRepository, never()).findByCursor(101L, 9001L, 20);
    }

    @Test
    void getConversationContext_shouldUseRedisSummaryWithoutDbPeekWhenConvVersionProvided() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn latestTurn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("user")
                .textContent("帮我解释一下")
                .createdAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findById(101L))
                .thenThrow(new AssertionError("should not peek conversation from db before redis summary hit"));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        String summaryKey = keyFactory.convSummaryKey(101L, 7L, 20);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> envelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build();
        when(redisContextCache.getConversationSummary(summaryKey)).thenReturn(Optional.of(envelope));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getConversationContext(101L, 7L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9001L);
        assertThat(view.getCacheHitLayer()).isEqualTo("redis");
        verify(redisContextCache).getConversationSummary(summaryKey);
        verify(conversationRepository, never()).findById(101L);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
    }

    @Test
    void getConversationContext_shouldUseDifferentSummaryKeysForDifferentMessageLimits() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn latestTurn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage latest20 = VerlaMessage.builder()
                .id(9020L)
                .conversationId(101L)
                .role("assistant")
                .textContent("limit 20")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaMessage latest50 = VerlaMessage.builder()
                .id(9050L)
                .conversationId(101L)
                .role("assistant")
                .textContent("limit 50")
                .createdAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findById(101L))
                .thenThrow(new AssertionError("should not peek conversation from db before redis summary hit"));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        String summaryKey20 = keyFactory.convSummaryKey(101L, 7L, 20);
        String summaryKey50 = keyFactory.convSummaryKey(101L, 7L, 50);
        when(redisContextCache.getConversationSummary(summaryKey20)).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(latest20)))
                        .build()));
        when(redisContextCache.getConversationSummary(summaryKey50)).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(latest50)))
                        .build()));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        VerlaSessionContextQueryOptions limit20 = VerlaSessionContextQueryOptions.builder().messageLimit(20).build();
        VerlaSessionContextQueryOptions limit50 = VerlaSessionContextQueryOptions.builder().messageLimit(50).build();

        var view20 = service.getConversationContext(101L, 7L, null, limit20);
        var view50 = service.getConversationContext(101L, 7L, null, limit50);

        assertThat(view20.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9020L);
        assertThat(view50.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9050L);
        verify(redisContextCache).getConversationSummary(summaryKey20);
        verify(redisContextCache).getConversationSummary(summaryKey50);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
        verify(messageRepository, never()).findByCursor(101L, null, 50);
    }

    @Test
    void getConversationContext_shouldKeepLatestAndHistoryPagesOnSeparateRedisKeys() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn latestTurn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage latestMessage = VerlaMessage.builder()
                .id(9100L)
                .conversationId(101L)
                .role("assistant")
                .textContent("最近页")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaMessage historyMessage = VerlaMessage.builder()
                .id(8999L)
                .conversationId(101L)
                .role("assistant")
                .textContent("历史页")
                .createdAt(LocalDateTime.now())
                .build();

        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(conversationRepository.findById(101L)).thenReturn(conversation);

        String summaryKey = keyFactory.convSummaryKey(101L, 7L, 20);
        String pageKey = keyFactory.convMessagesKey(101L, 7L, 9001L, 20);
        when(redisContextCache.getConversationSummary(summaryKey)).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(latestMessage)))
                        .build()));
        when(redisContextCache.getConversationMessagesPage(pageKey)).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationMessagesPageCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationMessagesPageCacheValue(List.of(historyMessage)))
                        .build()));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var latestView = service.getConversationContext(101L, 7L, null, null);
        var historyView = service.getConversationContext(101L, 7L, 9001L, null);

        assertThat(latestView.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9100L);
        assertThat(historyView.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(8999L);
        verify(redisContextCache).getConversationSummary(summaryKey);
        verify(redisContextCache).getConversationMessagesPage(pageKey);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
        verify(messageRepository, never()).findByCursor(101L, 9001L, 20);
    }

    @Test
    void getSessionContext_shouldUseLatestVersionFromRedisWhenConvVersionMissing() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("user")
                .textContent("帮我解释一下")
                .createdAt(LocalDateTime.now())
                .build();

        when(sessionRepository.findById(301L)).thenReturn(session);
        when(turnRepository.findById(201L)).thenReturn(turn);
        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listBySession(301L, 50)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        String latestVersionKey = keyFactory.convLatestVersionKey(101L);
        String summaryKey = keyFactory.convSummaryKey(101L, 7L, 20);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> envelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build();
        when(redisContextCache.getConversationLatestVersion(latestVersionKey)).thenReturn(Optional.of(7L));
        when(redisContextCache.getConversationSummary(summaryKey)).thenReturn(Optional.of(envelope));

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getSessionContext(301L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9001L);
        assertThat(view.getCacheHitLayer()).isEqualTo("redis");
        verify(redisContextCache).getConversationLatestVersion(latestVersionKey);
        verify(redisContextCache).getConversationSummary(summaryKey);
        verify(conversationRepository, never()).findById(101L);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
    }
}
