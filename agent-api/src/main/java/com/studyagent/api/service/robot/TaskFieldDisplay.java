package com.studyagent.api.service.robot;

import java.util.HashMap;
import java.util.Map;

/**
 * 与前端 {@code studyagent-clone/src/constants/options.ts} / {@code types/api.ts} 对齐的展示标签
 */
public final class TaskFieldDisplay {

    private static final Map<Integer, String> SUBJECT = new HashMap<>();
    private static final Map<Integer, String> ACADEMIC = new HashMap<>();
    private static final Map<Integer, String> CITATION = new HashMap<>();
    private static final Map<Integer, String> PAGE_LENGTH = new HashMap<>();

    static {
        SUBJECT.put(0, "Not Specified");
        SUBJECT.put(1, "English Literature");
        SUBJECT.put(2, "History");
        SUBJECT.put(3, "Psychology");
        SUBJECT.put(4, "Sociology");
        SUBJECT.put(5, "Business");
        SUBJECT.put(6, "Economics");
        SUBJECT.put(7, "Political Science");
        SUBJECT.put(8, "Philosophy");
        SUBJECT.put(9, "Biology");
        SUBJECT.put(10, "Chemistry");
        SUBJECT.put(11, "Physics");
        SUBJECT.put(12, "Mathematics");
        SUBJECT.put(13, "Computer Science");
        SUBJECT.put(14, "Engineering");
        SUBJECT.put(15, "Other");

        ACADEMIC.put(0, "Not Specified");
        ACADEMIC.put(1, "Freshman");
        ACADEMIC.put(2, "Sophomore");
        ACADEMIC.put(3, "Junior");
        ACADEMIC.put(4, "Senior");
        ACADEMIC.put(5, "Graduate");

        CITATION.put(0, "Not Specified");
        CITATION.put(1, "APA");
        CITATION.put(2, "MLA");
        CITATION.put(3, "Chicago");
        CITATION.put(4, "Harvard");
        CITATION.put(5, "IEEE");
        CITATION.put(6, "No Citations");

        PAGE_LENGTH.put(0, "Not Specified");
        PAGE_LENGTH.put(2, "1–2 pages");
        PAGE_LENGTH.put(5, "3–5 pages");
        PAGE_LENGTH.put(10, "6–10 pages");
        PAGE_LENGTH.put(20, "11–20 pages");
        PAGE_LENGTH.put(21, "20+ pages");
    }

    public static String subject(Integer code) {
        if (code == null) return "Not Specified";
        return SUBJECT.getOrDefault(code, "Not Specified");
    }

    public static String academicLevel(Integer code) {
        if (code == null) return "Not Specified";
        return ACADEMIC.getOrDefault(code, "Not Specified");
    }

    public static String citationStyle(Integer code) {
        if (code == null) return "Not Specified";
        return CITATION.getOrDefault(code, "Not Specified");
    }

    public static String pageLength(Integer code) {
        if (code == null) return "Not Specified";
        return PAGE_LENGTH.getOrDefault(code, "Not Specified");
    }

    private TaskFieldDisplay() {}
}
