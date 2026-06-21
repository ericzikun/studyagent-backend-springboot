package com.studyagent.common.log.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 日志工具类
 * 提供对象序列化、脱敏、截断等功能
 */
@Slf4j
public class LogUtil {
    
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();
    
    /**
     * 默认敏感字段
     */
    private static final Set<String> DEFAULT_SENSITIVE_FIELDS = new HashSet<>(Arrays.asList(
            "password", "pwd", "secret", "secretkey", "secretKey", "secret_key",
            "token", "accesstoken", "accessToken", "access_token",
            "authorization", "apikey", "apiKey", "api_key",
            "privatekey", "privateKey", "private_key",
            "credential", "credentials"
    ));
    
    /**
     * 将对象转为 JSON 字符串，支持脱敏和截断
     *
     * @param obj             要序列化的对象
     * @param sensitiveFields 需要脱敏的字段名
     * @param maxLength       最大长度，-1 表示不限制
     * @return JSON 字符串
     */
    public static String toJson(Object obj, String[] sensitiveFields, int maxLength) {
        if (obj == null) {
            return "null";
        }
        
        try {
            String json = GSON.toJson(obj);
            
            // 脱敏处理
            if (sensitiveFields != null && sensitiveFields.length > 0) {
                json = maskSensitiveFields(json, sensitiveFields);
            } else {
                json = maskSensitiveFields(json, DEFAULT_SENSITIVE_FIELDS.toArray(new String[0]));
            }
            
            return truncateForLog(normalizeForSingleLine(json), maxLength);
        } catch (Exception e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return truncateForLog(normalizeForSingleLine(String.valueOf(obj)), maxLength);
        }
    }
    
    /**
     * 将对象转为 JSON 字符串（使用默认配置）
     */
    public static String toJson(Object obj) {
        return toJson(obj, null, 4096);
    }
    
    /**
     * 脱敏处理
     */
    public static String maskSensitiveFields(String json, String[] sensitiveFields) {
        if (StringUtils.isBlank(json) || sensitiveFields == null || sensitiveFields.length == 0) {
            return json;
        }
        
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                maskJsonObject(element.getAsJsonObject(), new HashSet<>(Arrays.asList(sensitiveFields)));
                return GSON.toJson(element);
            }
        } catch (Exception e) {
            // 如果解析失败，使用正则表达式简单替换
            for (String field : sensitiveFields) {
                String lowerField = field.toLowerCase();
                // 匹配 "field":"value" 或 "field": "value" 格式
                json = json.replaceAll(
                        "\"" + field + "\"\\s*:\\s*\"[^\"]*\"",
                        "\"" + field + "\":\"******\""
                );
                // 匹配下划线格式
                String snakeField = camelToSnake(field);
                json = json.replaceAll(
                        "\"" + snakeField + "\"\\s*:\\s*\"[^\"]*\"",
                        "\"" + snakeField + "\":\"******\""
                );
            }
        }
        
        return json;
    }

    private static String normalizeForSingleLine(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String truncateForLog(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[TRUNCATED, total=" + value.length() + "]";
    }
    
    /**
     * 递归脱敏 JsonObject
     */
    private static void maskJsonObject(JsonObject jsonObject, Set<String> sensitiveFields) {
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            
            // 检查字段名是否匹配（忽略大小写和下划线格式）
            if (isSensitiveField(key, sensitiveFields)) {
                jsonObject.addProperty(key, "******");
            } else if (value.isJsonObject()) {
                maskJsonObject(value.getAsJsonObject(), sensitiveFields);
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonObject()) {
                        maskJsonObject(item.getAsJsonObject(), sensitiveFields);
                    }
                }
            }
        }
    }
    
    /**
     * 检查字段是否为敏感字段
     */
    private static boolean isSensitiveField(String fieldName, Set<String> sensitiveFields) {
        String lowerFieldName = fieldName.toLowerCase();
        String snakeFieldName = camelToSnake(fieldName).toLowerCase();
        
        for (String sensitive : sensitiveFields) {
            String lowerSensitive = sensitive.toLowerCase();
            String snakeSensitive = camelToSnake(sensitive).toLowerCase();
            
            if (lowerFieldName.equals(lowerSensitive) 
                    || lowerFieldName.equals(snakeSensitive)
                    || snakeFieldName.equals(lowerSensitive)
                    || snakeFieldName.equals(snakeSensitive)
                    || lowerFieldName.contains(lowerSensitive)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 驼峰转下划线
     */
    private static String camelToSnake(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
    
    /**
     * 格式化耗时（毫秒）
     */
    public static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
            return String.format("%.2fs", durationMs / 1000.0);
        } else {
            long minutes = durationMs / 60000;
            long seconds = (durationMs % 60000) / 1000;
            return String.format("%dm%ds", minutes, seconds);
        }
    }
    
    /**
     * 格式化文件大小
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2fKB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2fMB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
