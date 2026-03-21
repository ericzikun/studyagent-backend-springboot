package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.FileEntity;
import com.studyagent.infra.mapper.FileMapper;
import com.studyagent.service.domain.file.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

/**
 * 定期删除「已同步 OSS（有 oss_key）且超过保留天数」的本地用户上传文件，释放磁盘。
 * 默认关闭，需配置 file.storage.purge-enabled=true 且 OSS 已启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalUploadFilePurgeScheduler {

    private final FileMapper fileMapper;
    private final OssStorageService ossStorageService;

    @Value("${file.storage.purge-enabled:false}")
    private boolean purgeEnabled;

    @Value("${file.storage.local-retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${file.storage.purge-cron:0 0 3 * * ?}")
    public void purgeOldLocalUploads() {
        if (!purgeEnabled || retentionDays <= 0) {
            return;
        }
        if (!ossStorageService.isEnabled()) {
            log.debug("跳过本地上传清理：OSS 未启用");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long cursor = 0L;
        int totalDeleted = 0;
        while (true) {
            var batch = fileMapper.selectList(
                    new LambdaQueryWrapper<FileEntity>()
                            .isNotNull(FileEntity::getOssKey)
                            .ne(FileEntity::getOssKey, "")
                            .lt(FileEntity::getCreatedAt, cutoff)
                            .gt(FileEntity::getId, cursor)
                            .orderByAsc(FileEntity::getId)
                            .last("LIMIT 100"));
            if (batch.isEmpty()) {
                break;
            }
            for (FileEntity e : batch) {
                cursor = e.getId();
                String sp = e.getStoragePath();
                if (sp == null || sp.isBlank()) {
                    continue;
                }
                try {
                    Path p = Paths.get(sp);
                    if (Files.isRegularFile(p)) {
                        Files.delete(p);
                        totalDeleted++;
                        log.info("本地上传已清理: id={}, objectId={}, path={}", e.getId(), e.getObjectId(), p);
                    }
                } catch (Exception ex) {
                    log.warn("清理本地文件失败 objectId={} path={}: {}", e.getObjectId(), sp, ex.getMessage());
                }
            }
        }
        if (totalDeleted > 0) {
            log.info("本地上传清理完成，本次删除 {} 个本地文件", totalDeleted);
        }
    }
}
