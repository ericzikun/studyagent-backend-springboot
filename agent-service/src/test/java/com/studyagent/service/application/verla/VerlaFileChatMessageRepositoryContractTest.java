package com.studyagent.service.application.verla;

import com.studyagent.service.application.verla.dto.FileChatMessageMeta;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaFileChatMessageRepositoryContractTest {

    @Test
    void findFileChatByCursor_shouldOnlyReturnTargetObjectMessages() {
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        repository.save(fileChatMessage(11L, 1001L, "obj_a", "user", "A-1"));
        repository.save(fileChatMessage(12L, 1001L, "obj_b", "user", "B-1"));
        repository.save(fileChatMessage(13L, 1001L, "obj_a", "assistant", "A-2"));
        repository.save(fileChatMessage(14L, 1002L, "obj_a", "assistant", "other-conversation"));

        List<VerlaMessage> messages = repository.findFileChatByCursor(1001L, "obj_a", null, 20);

        assertThat(messages).extracting(VerlaMessage::getId).containsExactly(13L, 11L);
        assertThat(messages).extracting(VerlaMessage::getTextContent).containsExactly("A-2", "A-1");
    }

    @Test
    void findFileChatByCursor_shouldRespectCursorAndLimit() {
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        repository.save(fileChatMessage(21L, 1001L, "obj_a", "user", "A-1"));
        repository.save(fileChatMessage(22L, 1001L, "obj_a", "assistant", "A-2"));
        repository.save(fileChatMessage(23L, 1001L, "obj_a", "user", "A-3"));

        List<VerlaMessage> messages = repository.findFileChatByCursor(1001L, "obj_a", 23L, 1);

        assertThat(messages).extracting(VerlaMessage::getId).containsExactly(22L);
    }

    @Test
    void findByCursor_shouldExcludeFileChatMessagesFromMainConversationHistory() {
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        repository.save(mainMessage(31L, 1001L, "user", "main-1"));
        repository.save(fileChatMessage(32L, 1001L, "obj_a", "user", "file-chat-1"));
        repository.save(mainMessage(33L, 1001L, "assistant", "main-2"));
        repository.save(mainMessage(34L, 1002L, "assistant", "other-conversation"));

        List<VerlaMessage> messages = repository.findByCursor(1001L, null, 20);

        assertThat(messages).extracting(VerlaMessage::getId).containsExactly(33L, 31L);
        assertThat(messages).extracting(VerlaMessage::getTextContent).containsExactly("main-2", "main-1");
    }

    private static VerlaMessage fileChatMessage(Long id,
                                                Long conversationId,
                                                String objectId,
                                                String role,
                                                String text) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(conversationId)
                .role(role)
                .textContent(text)
                .metaJson(VerlaFileChatMetadataHelper.writeMessageMeta(FileChatMessageMeta.builder()
                        .scene(FileChatMessageMeta.SCENE_FILE_CHAT)
                        .objectId(objectId)
                        .build()))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static VerlaMessage mainMessage(Long id,
                                            Long conversationId,
                                            String role,
                                            String text) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(conversationId)
                .role(role)
                .textContent(text)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static final class InMemoryMessageRepository implements VerlaMessageRepository {
        private final Map<Long, VerlaMessage> store = new HashMap<>();

        @Override
        public VerlaMessage save(VerlaMessage message) {
            store.put(message.getId(), message);
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return store.get(id);
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            List<VerlaMessage> rows = new ArrayList<>(store.values());
            rows.removeIf(message -> !conversationId.equals(message.getConversationId()));
            rows.removeIf(message -> {
                FileChatMessageMeta meta = VerlaFileChatMetadataHelper.readMessageMeta(message.getMetaJson());
                return meta != null && FileChatMessageMeta.SCENE_FILE_CHAT.equals(meta.getScene());
            });
            rows.sort(Comparator.comparing(VerlaMessage::getId).reversed());
            return rows.stream()
                    .filter(message -> cursor == null || message.getId() < cursor)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            List<VerlaMessage> rows = new ArrayList<>(store.values());
            rows.removeIf(message -> !conversationId.equals(message.getConversationId()));
            rows.removeIf(message -> {
                FileChatMessageMeta meta = VerlaFileChatMetadataHelper.readMessageMeta(message.getMetaJson());
                return meta == null
                        || !FileChatMessageMeta.SCENE_FILE_CHAT.equals(meta.getScene())
                        || !objectId.equals(meta.getObjectId());
            });
            rows.sort(Comparator.comparing(VerlaMessage::getId).reversed());
            return rows.stream()
                    .filter(message -> cursor == null || message.getId() < cursor)
                    .limit(limit)
                    .toList();
        }
    }
}
