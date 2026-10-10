package com.studyagent.api.dto.verla.request;

import lombok.Data;

/**
 * Video Tutor 发起一轮白板教学的请求。
 * <p>
 * <b>刻意不加 {@code @NotBlank}</b>：第一轮学生还没说话（进来就是"开始讲吧"），
 * 空串必须放行 —— Python 侧会把它回落成固定开场指令，保证外发给模型的序列
 * 仍是 {@code [system, user]}。这里拦掉的话，第一次打开页面就发不出请求。
 */
@Data
public class VideoTutorTurnRequest {

    /** 学生这句话；可为空（首轮）。 */
    private String message;

    /**
     * 授课语言偏好（前端传当前的 UI locale，如 `zh-CN` / `en`）。
     *
     * 必须每轮都传：白板只有中英两套语言规则（后端 resolve_board_lang 归到 zh/en），
     * 而 Java 侧原来的取值链路只读 conversation.workspace_json.outputLanguage —— 创建接口
     * 那条路根本没把语言写进去（`String.valueOf(Map)` 产出的还不是合法 JSON），所以不传就永远是英文。
     * 允许别名 / 大小写，落库前由 OutputLanguage.fromRaw 归一化。
     */
    private String outputLanguage;
}
