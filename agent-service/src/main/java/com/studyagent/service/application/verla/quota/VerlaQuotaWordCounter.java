package com.studyagent.service.application.verla.quota;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2 verla 链路 word 数计算。
 * <p>
 * 与 {@code HumanizerApplicationService.countWords / HumanizerTaskWorker.countWords} 一致：
 * <ul>
 *   <li>CJK（中日韩）字符：每个字算 1 word；</li>
 *   <li>非 CJK 部分：按空格分词，每个非空 token 算 1 word；</li>
 *   <li>混合文本两者相加。</li>
 * </ul>
 * 抽到独立 Component，保证 V1 / V2 计费口径完全相同，避免「同一段文本两边数不一样」。
 */
@Component
public class VerlaQuotaWordCounter {

    /** 中文 / CJK 扩展 A / 平假名 / 片假名 / 韩文 */
    private static final String CJK_PATTERN_STR =
            "[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]";

    private static final Pattern CJK = Pattern.compile(CJK_PATTERN_STR);

    public long countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher m = CJK.matcher(text);
        long cjk = 0;
        while (m.find()) {
            cjk++;
        }
        String nonCjk = text.replaceAll(CJK_PATTERN_STR, " ").trim();
        long eng = nonCjk.isEmpty()
                ? 0
                : Arrays.stream(nonCjk.split("\\s+"))
                        .filter(w -> !w.isEmpty())
                        .count();
        return cjk + eng;
    }
}
