package com.studyagent.service.application.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.state.ConversationStateMachine;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Verla Conversation 应用服务（PR-6 / PR-7）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §11.1 / §21.1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaConversationService {

    private final VerlaConversationRepository conversationRepository;
    private final VerlaMessageRepository messageRepository;
    private final ConversationStateMachine conversationStateMachine;

    @Transactional
    public VerlaConversation create(String userId, String title, String workspaceJson, String primaryIntent) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        LocalDateTime now = LocalDateTime.now();
        String intent = primaryIntent == null || primaryIntent.isBlank() ? null : primaryIntent.trim();
        VerlaConversation c = VerlaConversation.builder()
                .userId(userId)
                .title(title == null || title.isBlank() ? "新对话" : title)
                .status(ConversationStatus.ACTIVE.getDbValue())
                .primaryIntent(intent)
                .workspaceJson(workspaceJson)
                .turnCount(0)
                .version(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        VerlaConversation saved = conversationRepository.save(c);
        log.info("[Verla] 新建 conversation: id={}, userId={}", saved.getId(), userId);
        return saved;
    }

    public List<VerlaConversation> list(String userId, int page, int size) {
        return conversationRepository.findByUserPaged(userId, page, size);
    }

    public VerlaConversation getOwned(String userId, Long conversationId) {
        VerlaConversation c = conversationRepository.findById(conversationId);
        if (c == null || ConversationStatus.fromDb(c.getStatus()) == ConversationStatus.DELETED) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        if (!c.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        return c;
    }

    public VerlaConversation getForInternal(Long conversationId) {
        VerlaConversation c = conversationRepository.findById(conversationId);
        if (c == null || ConversationStatus.fromDb(c.getStatus()) == ConversationStatus.DELETED) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        return c;
    }

    @Transactional
    public VerlaConversation rename(String userId, Long conversationId, String newTitle) {
        VerlaConversation c = getOwned(userId, conversationId);
        c.setTitle(newTitle);
        c.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(c);
    }

    @Transactional
    public VerlaConversation archive(String userId, Long conversationId) {
        VerlaConversation c = getOwned(userId, conversationId);
        ConversationStatus next = conversationStateMachine.archive(ConversationStatus.fromDb(c.getStatus()));
        c.setStatus(next.getDbValue());
        c.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(c);
    }

    @Transactional
    public VerlaConversation restore(String userId, Long conversationId) {
        VerlaConversation c = getOwned(userId, conversationId);
        ConversationStatus next = conversationStateMachine.restore(ConversationStatus.fromDb(c.getStatus()));
        c.setStatus(next.getDbValue());
        c.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(c);
    }

    @Transactional
    public void softDelete(String userId, Long conversationId) {
        VerlaConversation c = getOwned(userId, conversationId);
        ConversationStatus next = conversationStateMachine.delete(ConversationStatus.fromDb(c.getStatus()));
        c.setStatus(next.getDbValue());
        c.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(c);
    }

    /**
     * 历史消息游标分页（cursor=null 取最新一页；返回按 id desc 排序）
     */
    public List<VerlaMessage> listMessages(Long conversationId, Long cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return messageRepository.findByCursor(conversationId, cursor, safeLimit);
    }

    /**
     * 校验 conversation 可写（active 才允许新增 turn / message）
     */
    public VerlaConversation loadWritable(String userId, Long conversationId) {
        VerlaConversation c = getOwned(userId, conversationId);
        if (ConversationStatus.fromDb(c.getStatus()) != ConversationStatus.ACTIVE) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE);
        }
        return c;
    }
}
