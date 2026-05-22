package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaSlidesConvertCommandService {

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    private static final String DEFAULT_COMMAND_EXCHANGE = "studyagent.command";
    private static final String SOURCE_KIND = "assignment_slides_pptxgenjs";
    private static final String TARGET_KIND = "assignment_slides_editor_json";
    private static final String SOURCE_FILENAME_FALLBACK = "deck.js";
    private static final String TARGET_SUMMARY_FALLBACK = "deck.editor.json";

    private final MqOutboxService mqOutboxService;
    private final VerlaArtifactRepository artifactRepository;

    @Value("${verla.mq.command-exchange:" + DEFAULT_COMMAND_EXCHANGE + "}")
    private String commandExchange;

    public void triggerIfNeeded(VerlaArtifact sourceArtifact, String sourceBody) {
        if (!isEligibleSource(sourceArtifact, sourceBody)) {
            return;
        }

        String targetArtifactUid = buildTargetArtifactUid(sourceArtifact);
        if (targetArtifactUid.equals(sourceArtifact.getArtifactUid())) {
            log.warn("[Verla/slides-convert] target artifact uid equals source uid, skip uid={}",
                    sourceArtifact.getArtifactUid());
            return;
        }
        if (artifactRepository.findByUid(targetArtifactUid) != null) {
            log.info("[Verla/slides-convert] target already exists, skip sourceUid={} targetUid={}",
                    sourceArtifact.getArtifactUid(), targetArtifactUid);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceArtifactUid", sourceArtifact.getArtifactUid());
        payload.put("targetArtifactUid", targetArtifactUid);
        payload.put("conversationId", sourceArtifact.getConversationId());
        payload.put("turnId", sourceArtifact.getTurnId());
        payload.put("sessionId", sourceArtifact.getSessionId());
        payload.put("sourceObjectId", sourceArtifact.getSourceObjectId());
        payload.put("sourceKind", sourceArtifact.getKind());
        payload.put("sourceFilename", deriveSourceFilename(sourceArtifact));
        payload.put("targetKind", TARGET_KIND);
        payload.put("targetSummary", TARGET_SUMMARY_FALLBACK);
        payload.put("sourceBody", sourceBody);

        VerlaCommandEnvelope env = VerlaCommandEnvelope.builder()
                .schemaVersion(1)
                .messageId("cmd-" + UUID.randomUUID())
                .correlationId("conv:" + sourceArtifact.getConversationId()
                        + ":turn:" + sourceArtifact.getTurnId()
                        + ":sess:" + sourceArtifact.getSessionId())
                .orderingKey("session:" + sourceArtifact.getSessionId())
                .action(VerlaCommandAction.CMD_SLIDES_CONVERT_TO_EDITOR_JSON.getCode())
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(INSTANCE_ID)
                        .build())
                .conversation(VerlaConversationRef.builder()
                        .conversationId(sourceArtifact.getConversationId())
                        .build())
                .turn(VerlaTurnRef.builder()
                        .turnId(sourceArtifact.getTurnId())
                        .build())
                .session(VerlaSessionRef.builder()
                        .sessionId(sourceArtifact.getSessionId())
                        .kind(VerlaSessionKind.ASSIGNMENT)
                        .feature("slides_convert")
                        .build())
                .payload(payload)
                .build();

        mqOutboxService.createVerlaCommand(
                env,
                commandExchange,
                VerlaCommandAction.CMD_SLIDES_CONVERT_TO_EDITOR_JSON.getCode());
        log.info("[Verla/slides-convert] command queued sourceUid={} targetUid={} sessionId={}",
                sourceArtifact.getArtifactUid(), targetArtifactUid, sourceArtifact.getSessionId());
    }

    public String targetArtifactUidFor(VerlaArtifact sourceArtifact) {
        return buildTargetArtifactUid(sourceArtifact);
    }

    private boolean isEligibleSource(VerlaArtifact sourceArtifact, String sourceBody) {
        if (sourceArtifact == null) {
            return false;
        }
        if (!SOURCE_KIND.equals(sourceArtifact.getKind())) {
            return false;
        }
        if (sourceArtifact.getConversationId() == null
                || sourceArtifact.getTurnId() == null
                || sourceArtifact.getSessionId() == null) {
            return false;
        }
        if (sourceBody == null || sourceBody.isBlank()) {
            log.warn("[Verla/slides-convert] source body empty, skip uid={}",
                    sourceArtifact.getArtifactUid());
            return false;
        }
        return true;
    }

    private String buildTargetArtifactUid(VerlaArtifact sourceArtifact) {
        String uid = sourceArtifact.getArtifactUid();
        if (uid != null && uid.contains("slides_pptxgenjs")) {
            return uid.replace("slides_pptxgenjs", "slides_editor_json");
        }
        if (uid != null && uid.endsWith("_slides_source")) {
            return uid + "_editor_json";
        }
        if (uid != null && !uid.isBlank()) {
            return uid + "_slides_editor_json";
        }
        return "artifact_" + sourceArtifact.getConversationId() + "_"
                + sourceArtifact.getTurnId() + "_" + sourceArtifact.getSessionId()
                + "_slides_editor_json";
    }

    private String deriveSourceFilename(VerlaArtifact sourceArtifact) {
        String summary = sourceArtifact.getSummary();
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return SOURCE_FILENAME_FALLBACK;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
