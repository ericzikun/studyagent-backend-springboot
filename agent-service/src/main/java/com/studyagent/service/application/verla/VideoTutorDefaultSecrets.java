package com.studyagent.service.application.verla;

import java.io.InputStream;
import java.util.Properties;

/**
 * 临时测试默认密钥（video-tutor-defaults.properties）。
 * 部署验证通过后应删除资源文件并改回环境变量 / models.yaml。
 */
public final class VideoTutorDefaultSecrets {

    private static volatile Properties properties;

    private VideoTutorDefaultSecrets() {
    }

    public static String get(String key) {
        Properties props = properties;
        if (props == null) {
            synchronized (VideoTutorDefaultSecrets.class) {
                props = properties;
                if (props == null) {
                    props = new Properties();
                    try (InputStream in = VideoTutorDefaultSecrets.class
                            .getClassLoader().getResourceAsStream("video-tutor-defaults.properties")) {
                        if (in != null) {
                            props.load(in);
                        }
                    } catch (Exception ignored) {
                        // 没有默认值就走空串
                    }
                    properties = props;
                }
            }
        }
        return props.getProperty(key, "").trim();
    }
}
