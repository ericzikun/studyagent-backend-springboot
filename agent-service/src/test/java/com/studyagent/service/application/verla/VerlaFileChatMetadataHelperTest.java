package com.studyagent.service.application.verla;

import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatMessageMeta;
import com.studyagent.service.application.verla.dto.FileChatPanelState;
import com.studyagent.service.domain.verla.VerlaAttachment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaFileChatMetadataHelperTest {

    @Test
    void readAttachmentState_shouldFallbackToPendingWhenMetaMissing() {
        VerlaAttachment attachment = VerlaAttachment.builder()
                .objectId("obj_123")
                .build();

        FileChatPanelState state = VerlaFileChatMetadataHelper.readAttachmentState(attachment);

        assertThat(state.getAnalysis()).isEqualTo(FileChatAnalysisState.pending());
        assertThat(state.getSuggestedQuestions()).isEmpty();
    }

    @Test
    void readAttachmentState_shouldParseAnalysisAndQuestionsFromMetaJson() {
        VerlaAttachment attachment = VerlaAttachment.builder()
                .objectId("obj_123")
                .metaJson("""
                        {
                          "pageCount": 3,
                          "fileChat": {
                            "analysisStatus": "READY",
                            "analysisText": "这是题目文件，和当前 assignment 的 essay prompt 有直接关系。",
                            "suggestedQuestions": [
                              "帮我比较这四道题",
                              "提取所有格式要求"
                            ],
                            "updatedAt": "2026-05-20T20:00:00"
                          }
                        }
                        """)
                .build();

        FileChatPanelState state = VerlaFileChatMetadataHelper.readAttachmentState(attachment);

        assertThat(state.getAnalysis())
                .isEqualTo(FileChatAnalysisState.builder()
                        .status(FileChatAnalysisStatus.READY)
                        .text("这是题目文件，和当前 assignment 的 essay prompt 有直接关系。")
                        .build());
        assertThat(state.getSuggestedQuestions()).containsExactly(
                "帮我比较这四道题",
                "提取所有格式要求");
    }

    @Test
    void writeAttachmentState_shouldPreserveOtherMetaFields() {
        FileChatPanelState state = FileChatPanelState.builder()
                .analysis(FileChatAnalysisState.builder()
                        .status(FileChatAnalysisStatus.FAILED)
                        .text("文件解析失败，请稍后重试。")
                        .build())
                .suggestedQuestions(List.of("重新解析这个文件"))
                .updatedAt("2026-05-20T20:10:00")
                .build();

        String metaJson = VerlaFileChatMetadataHelper.writeAttachmentState("""
                {
                  "pageCount": 5,
                  "source": "upload"
                }
                """, state);

        assertThat(metaJson).contains("\"pageCount\":5");
        assertThat(metaJson).contains("\"source\":\"upload\"");
        assertThat(metaJson).contains("\"fileChat\"");
        assertThat(metaJson).contains("\"analysisStatus\":\"FAILED\"");
        assertThat(metaJson).contains("\"analysisText\":\"文件解析失败，请稍后重试。\"");
        assertThat(metaJson).contains("\"suggestedQuestions\":[\"重新解析这个文件\"]");
    }

    @Test
    void messageMeta_shouldRoundTripFileChatFields() {
        FileChatMessageMeta meta = FileChatMessageMeta.builder()
                .scene(FileChatMessageMeta.SCENE_FILE_CHAT)
                .objectId("obj_123")
                .build();

        String metaJson = VerlaFileChatMetadataHelper.writeMessageMeta(meta);
        FileChatMessageMeta parsed = VerlaFileChatMetadataHelper.readMessageMeta(metaJson);

        assertThat(parsed).isEqualTo(meta);
    }
}
