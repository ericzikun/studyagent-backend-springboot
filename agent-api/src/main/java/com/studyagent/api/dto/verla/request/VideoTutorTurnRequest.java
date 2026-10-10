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
}
