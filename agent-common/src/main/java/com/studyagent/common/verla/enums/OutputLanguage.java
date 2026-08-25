package com.studyagent.common.verla.enums;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 输出语言偏好（前端 UI 偏好 → MQ payload.outputLanguage）。
 * <p>
 * 与 Python 侧 {@code app/services/output_language.py::OUTPUT_LANGUAGES} 同源：
 * 值为小写完整语言名，随 {@code outputLanguage} 顶层 payload 字段透传给 Python，
 * CAMEL ChatAgent 会把 "you must output text in {value}." 注入 system message。
 * <p>
 * 别名归一化（ISO-639 / BCP-47，大小写不敏感）：{@code en}→{@code english}、
 * {@code zh-CN}→{@code chinese}、{@code zh-TW}→{@code traditional-chinese} 等。
 * 未知 / 空值一律回退 {@link #ENGLISH}（缺省保持现状，用户显式选择才改变输出语言）。
 */
public enum OutputLanguage {

    ENGLISH("english"),
    CHINESE("chinese"),
    TRADITIONAL_CHINESE("traditional-chinese"),
    SPANISH("spanish"),
    FRENCH("french"),
    GERMAN("german"),
    ITALIAN("italian"),
    PORTUGUESE("portuguese"),
    JAPANESE("japanese"),
    KOREAN("korean"),
    RUSSIAN("russian"),
    VIETNAMESE("vietnamese"),
    INDONESIAN("indonesian"),
    TURKISH("turkish"),
    HINDI("hindi"),
    MALAY("malay"),
    FILIPINO("filipino");

    /** MQ payload / workspace_json 中使用的规范值（小写完整语言名）。 */
    private final String value;

    OutputLanguage(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** ISO-639 / BCP-47 别名 → 规范值（小写 key）。 */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("en", "english");
        ALIASES.put("en-us", "english");
        ALIASES.put("en-gb", "english");
        ALIASES.put("en-au", "english");
        ALIASES.put("en-ca", "english");
        ALIASES.put("zh", "chinese");
        ALIASES.put("zh-cn", "chinese");
        ALIASES.put("zh-hans", "chinese");
        ALIASES.put("zh-hans-cn", "chinese");
        ALIASES.put("zh-sg", "chinese");
        ALIASES.put("zh-tw", "traditional-chinese");
        ALIASES.put("zh-hant", "traditional-chinese");
        ALIASES.put("zh-hant-tw", "traditional-chinese");
        ALIASES.put("zh-hk", "traditional-chinese");
        ALIASES.put("zh-mo", "traditional-chinese");
        ALIASES.put("es", "spanish");
        ALIASES.put("es-es", "spanish");
        ALIASES.put("es-mx", "spanish");
        ALIASES.put("es-us", "spanish");
        ALIASES.put("es-419", "spanish");
        ALIASES.put("fr", "french");
        ALIASES.put("fr-fr", "french");
        ALIASES.put("fr-ca", "french");
        ALIASES.put("de", "german");
        ALIASES.put("de-de", "german");
        ALIASES.put("de-at", "german");
        ALIASES.put("de-ch", "german");
        ALIASES.put("it", "italian");
        ALIASES.put("it-it", "italian");
        ALIASES.put("pt", "portuguese");
        ALIASES.put("pt-br", "portuguese");
        ALIASES.put("pt-pt", "portuguese");
        ALIASES.put("ja", "japanese");
        ALIASES.put("ja-jp", "japanese");
        ALIASES.put("ko", "korean");
        ALIASES.put("ko-kr", "korean");
        ALIASES.put("ru", "russian");
        ALIASES.put("ru-ru", "russian");
        ALIASES.put("vi", "vietnamese");
        ALIASES.put("vi-vn", "vietnamese");
        ALIASES.put("id", "indonesian");
        ALIASES.put("id-id", "indonesian");
        ALIASES.put("tr", "turkish");
        ALIASES.put("tr-tr", "turkish");
        ALIASES.put("hi", "hindi");
        ALIASES.put("hi-in", "hindi");
        ALIASES.put("ms", "malay");
        ALIASES.put("ms-my", "malay");
        ALIASES.put("ms-sg", "malay");
        ALIASES.put("fil", "filipino");
        ALIASES.put("fil-ph", "filipino");
        ALIASES.put("tl", "filipino");
        ALIASES.put("tl-ph", "filipino");
    }

    /**
     * 按规范值 / 别名解析输出语言；未知或空白一律回退 {@link #ENGLISH}。
     */
    public static OutputLanguage fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return ENGLISH;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (OutputLanguage language : values()) {
            if (language.value.equals(key)) {
                return language;
            }
        }
        String canonical = ALIASES.get(key);
        if (canonical == null) {
            return ENGLISH;
        }
        for (OutputLanguage language : values()) {
            if (language.value.equals(canonical)) {
                return language;
            }
        }
        return ENGLISH;
    }
}
