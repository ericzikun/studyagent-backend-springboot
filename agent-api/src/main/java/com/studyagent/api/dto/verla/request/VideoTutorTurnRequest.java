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

    /**
     * 学生是否开启了声音（前端开关）。
     * <p>
     * 每轮都带、<b>不落库</b>：它是"这一轮要不要朗读"的即时意图，不是会话属性 ——
     * 学生随时可以关掉（例如进了图书馆），下一轮就该立刻安静。
     * <p>
     * 缺省 / null 一律按 <b>false</b> 处理：语音是增量能力，老客户端不带这个字段时，
     * 链路必须与加语音之前完全一致（Python 侧一次合成都不会发生）。
     */
    private Boolean soundEnabled;

    /**
     * 教学讲法：{@code guided / socratic / coach / self_paced / explore}。
     * <p>
     * 每轮都带、不落库：Python 无状态，讲法随 command 下发；缺省/未知值由 Python
     * 归一化回落 {@code guided}。学生可在会话中随时切换，下一轮生效。
     */
    private String teachingStyle;

    /**
     * 学生刚切换了讲法：下一轮答题前先用一句话确认新讲法。
     * 仅切换后的第一轮为 true，之后为 false。
     */
    private Boolean styleChanged;

    /**
     * 语音由谁合成。{@code java} = 前端直接请求 Java 的 TTS 流，本轮不要 Python 合成；
     * 缺省 / 其他值保持旧链路（Python 合成 → OSS → MQ）。
     */
    private String ttsProvider;
}
