package com.studyagent.service.application.verla.clone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloneRenderersTest {

    @Test
    void graphRendersAxesCurveAndPoints() {
        JsonObject spec = JsonParser.parseString("""
                {"functions":[{"expr":"x^2","label":"y=x^2"}],
                 "domain":[-3,3],"points":[{"x":1,"y":1,"label":"A"}],
                 "xLabel":"x","yLabel":"y"}
                """).getAsJsonObject();
        String svg = CloneGraphRenderer.render(spec, 440, 330);
        assertThat(svg).contains("data-name=\"coordinate-plane\"");
        assertThat(svg).contains("<path");
        assertThat(svg).contains(">A</text>");
        assertThat(svg).doesNotContain("NaN");
    }

    @Test
    void graphSupportsImplicitMultiplicationAndUnicodePower() {
        JsonObject spec = JsonParser.parseString("""
                {"functions":[{"expr":"2x²+sin(x)","label":"f"}],"domain":[-2,2]}
                """).getAsJsonObject();
        String svg = CloneGraphRenderer.render(spec, 440, 330);
        assertThat(svg).doesNotContain("NaN");
        assertThat(svg).contains("<path");
    }

    @Test
    void sceneRendersBoxesArrowsAndLabels() {
        JsonObject scene = JsonParser.parseString("""
                {"title":"RAG","items":[
                  {"type":"box","x":30,"y":120,"w":120,"h":60,"text":"提问","color":"blue"},
                  {"type":"arrow","x1":150,"y1":150,"x2":250,"y2":150,"label":"检索","color":"purple"},
                  {"type":"circle","cx":300,"cy":150,"r":40,"text":"答案","color":"green"}
                ]}
                """).getAsJsonObject();
        String svg = CloneDiagramRenderer.renderScene(scene);
        assertThat(svg).contains("data-name=\"scene-diagram\"");
        assertThat(svg).contains(">提问</text>");
        assertThat(svg).contains(">检索</text>");
        assertThat(svg).contains(">答案</text>");
    }

    @Test
    void fullCanvasFrameIsStrippedAndUnsafeSvgRejected() {
        String framed = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 440 330\">"
                + "<rect x=\"0\" y=\"0\" width=\"440\" height=\"330\" fill=\"#fff\"/>"
                + "<circle cx=\"220\" cy=\"165\" r=\"40\"/></svg>";
        String stripped = CloneDiagramRenderer.stripFullCanvasFrames(framed);
        assertThat(stripped).doesNotContain("width=\"440\" height=\"330\"");
        assertThat(stripped).contains("<circle");
        assertThat(CloneDiagramRenderer.valid(framed)).isTrue();
        assertThat(CloneDiagramRenderer.valid("<svg><script>alert(1)</script></svg>")).isFalse();
    }

    @Test
    void extractsJsonObjectFromFencedResponse() throws Exception {
        JsonObject obj = CloneLlmClient.extractJsonObject("```json\n{\"items\":[{\"type\":\"box\"}]}\n```");
        assertThat(obj.getAsJsonArray("items")).hasSize(1);
    }
}
