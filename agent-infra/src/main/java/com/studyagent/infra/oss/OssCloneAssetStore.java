package com.studyagent.infra.oss;

import com.studyagent.service.application.verla.clone.CloneAssetStore;
import com.studyagent.service.domain.file.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** clone 示意图 SVG 落 OSS；公开读桶即可直接被白板 <img>/<svg> 拉取。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssCloneAssetStore implements CloneAssetStore {

    private final OssStorageService ossService;

    @Override
    public String storeSvg(String svg, String name) {
        if (svg == null || svg.isBlank() || !ossService.isEnabled()) {
            return null;
        }
        String safe = (name == null ? "diagram" : name).replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = "studyagent/video_tutor/diagrams/"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                + "-" + safe + ".svg";
        try {
            if (!ossService.putBytesAtKey(key, svg.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return ossService.getOssUrl(key);
        } catch (Exception e) {
            log.warn("[video-tutor/clone] store svg failed: {}", e.getMessage());
            return null;
        }
    }
}
