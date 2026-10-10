package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 白板 artifact 的<b>布局唯一真相</b>：uid 怎么拼、kind 是什么、树和引用号计数器存在哪。
 * <p>
 * 抽出来是因为至少三处要用它 —— 派发时读（{@code VerlaTurnOrchestrator}）、
 * 终态写（{@code VideoTutorEventHandler}）、刷新恢复时读（{@code VideoTutorSnapshotService}）。
 * 三份各自实现的下场是：改了一处 uid 拼法，另两处静默读不到板子（不报错，只是每次都是空板）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTutorBoardStore {

    /** 会话级单行：一个 conversation 只有一块板，{@code uk_artifact_uid} 因此天然幂等。 */
    public static final String KIND = "video_tutor_board";

    private final VerlaArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public static String uid(Long conversationId) {
        return "board_" + conversationId;
    }

    public VerlaArtifact find(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return artifactRepository.findByUid(uid(conversationId));
    }

    /**
     * 下一个要写入的版本号 = 现存 + 1（首行 1）。
     * <p>
     * 由服务端自算、不采信 Python 回显：{@code upsertByUid} 的策略是"低 version 直接丢弃"，
     * 照写回显值会让整轮板子在 Python 看到的版本落后时被静默丢掉。
     */
    public int nextVersion(Long conversationId) {
        VerlaArtifact existing = find(conversationId);
        Integer current = existing == null ? null : existing.getVersion();
        return current == null ? 1 : current + 1;
    }

    /**
     * 读白板状态。读不出来时返回空板 + 计数器 0，并打 warn —— <b>不抛异常</b>：
     * 读不到板子还能从零讲，把整轮打成 FAILED 才是更差的结果。
     */
    public BoardState read(Long conversationId) {
        return parse(find(conversationId), conversationId, objectMapper);
    }

    /**
     * 纯解析（静态，不碰仓储）：给已经持有 artifact 与 ObjectMapper 的调用方用，
     * 这样它们不必为了解析再注入本组件。
     */
    public static BoardState parse(VerlaArtifact artifact, Long conversationId, ObjectMapper mapper) {
        if (artifact == null) {
            return BoardState.empty();
        }
        try {
            List<Map<String, Object>> board = parseBoard(artifact.getBodyOrRef(), mapper);
            return new BoardState(board, parseRefSeq(artifact.getMetaJson(), mapper),
                    artifact.getVersion() == null ? 0 : artifact.getVersion());
        } catch (Exception e) {
            log.warn("[Verla/videoTutor] board artifact unreadable cid={} uid={}: {} —— 按空板下发，"
                            + "Python 会从零重建，但引用号计数器会被重置",
                    conversationId, artifact.getArtifactUid(), e.getMessage());
            return BoardState.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseBoard(String bodyOrRef, ObjectMapper mapper) throws Exception {
        if (bodyOrRef == null || bodyOrRef.isBlank()) {
            return List.of();
        }
        List<?> raw = mapper.readValue(bodyOrRef, List.class);
        return raw.stream().map(i -> (Map<String, Object>) i).toList();
    }

    private static int parseRefSeq(String metaJson, ObjectMapper mapper) throws Exception {
        if (metaJson == null || metaJson.isBlank()) {
            return 0;
        }
        Object refSeq = mapper.readValue(metaJson, Map.class).get("refSeq");
        if (refSeq instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /** 引用号计数器随板子一起续上；复用旧号会让陈旧的 {@code of:"b3"} 指向擦黑板后新写的条目。 */
    public record BoardState(List<Map<String, Object>> board, int refSeq, int boardVersion) {
        public static BoardState empty() {
            return new BoardState(List.of(), 0, 0);
        }
    }
}
