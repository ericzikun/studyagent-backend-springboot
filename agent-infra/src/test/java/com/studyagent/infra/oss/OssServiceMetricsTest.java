package com.studyagent.infra.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.studyagent.infra.metrics.ExternalDependencyMetrics;
import com.studyagent.service.domain.file.FileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OssServiceMetricsTest {

    @Test
    void put_bytes_records_one_oss_attempt_when_client_fails() {
        OssConfig config = new OssConfig();
        config.setEnabled(true);
        config.setBucketName("test-bucket");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FileRepository fileRepository = (FileRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {FileRepository.class}, (proxy, method, args) -> null);
        OssService service = new OssService(config, fileRepository, new ExternalDependencyMetrics(meterRegistry));
        OSS client = (OSS) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OSS.class},
                (proxy, method, args) -> {
                    if ("putObject".equals(method.getName())) {
                        throw new ClientException("hidden");
                    }
                    return null;
                });
        ReflectionTestUtils.setField(service, "ossClient", client);

        assertEquals(false, service.putBytesAtKey("verla/test.txt", new byte[] {1}));
        assertEquals(1.0, meterRegistry.get("studyagent.external.requests")
                .tags("dependency", "oss", "operation", "put", "result", "error", "error_type", "oss")
                .counter().count());
        assertEquals(1, meterRegistry.find("studyagent.external.request.duration").timers().size());
    }

    @Test
    void object_exists_records_one_head_attempt_for_success_and_client_failure() {
        OssConfig config = new OssConfig();
        config.setEnabled(true);
        config.setBucketName("test-bucket");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FileRepository fileRepository = (FileRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {FileRepository.class}, (proxy, method, args) -> null);
        OssService service = new OssService(config, fileRepository, new ExternalDependencyMetrics(meterRegistry));
        OSS client = (OSS) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OSS.class},
                (proxy, method, args) -> {
                    if ("doesObjectExist".equals(method.getName())) {
                        if ("missing".equals(args[1])) {
                            throw new ClientException("hidden");
                        }
                        return true;
                    }
                    return null;
                });
        ReflectionTestUtils.setField(service, "ossClient", client);

        assertEquals(true, service.objectExists("present"));
        assertEquals(false, service.objectExists("missing"));
        assertEquals(1.0, meterRegistry.get("studyagent.external.requests")
                .tags("dependency", "oss", "operation", "head", "result", "success", "error_type", "none")
                .counter().count());
        assertEquals(1.0, meterRegistry.get("studyagent.external.requests")
                .tags("dependency", "oss", "operation", "head", "result", "error", "error_type", "oss")
                .counter().count());
    }
}
