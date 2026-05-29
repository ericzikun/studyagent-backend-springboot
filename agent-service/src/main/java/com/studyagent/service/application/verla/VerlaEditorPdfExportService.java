package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VerlaEditorPdfExportService {

    private final RestTemplate restTemplate;
    private final String conversionBaseUrl;
    private final Semaphore concurrencySemaphore;

    public VerlaEditorPdfExportService(
            @Value("${verla.editor.pdf-export.conversion-base-url:}") String conversionBaseUrl,
            @Value("${verla.editor.pdf-export.max-concurrent:2}") int maxConcurrent) {

        if (conversionBaseUrl == null || conversionBaseUrl.isBlank()) {
            log.error("[PDF Export] conversion-base-url is not configured — PDF export will fail");
        }

        this.conversionBaseUrl = conversionBaseUrl != null && conversionBaseUrl.endsWith("/")
                ? conversionBaseUrl.substring(0, conversionBaseUrl.length() - 1)
                : conversionBaseUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);

        this.concurrencySemaphore = new Semaphore(Math.max(1, maxConcurrent));
        log.info("[PDF Export] Initialized — conversionBaseUrl={}, maxConcurrent={}",
                this.conversionBaseUrl, maxConcurrent);
    }

    public byte[] renderTiptapToPdf(String title, Object document) {
        long startTime = System.currentTimeMillis();

        if (conversionBaseUrl == null || conversionBaseUrl.isBlank()) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF export service is not configured (missing conversion-base-url)");
        }

        boolean acquired = false;
        try {
            acquired = concurrencySemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF export interrupted");
        }

        if (!acquired) {
            log.warn("[PDF Export] Concurrency limit reached — rejecting request");
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF export is busy, please try again.");
        }

        try {
            String url = conversionBaseUrl + "/api/document-editor/export/pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ObjectNode body = new ObjectMapper().createObjectNode();
            body.put("title", title);
            body.set("document", new ObjectMapper().valueToTree(document));

            HttpEntity<String> requestEntity = new HttpEntity<>(body.toString(), headers);

            log.info("[PDF Export] Sending Tiptap JSON to conversion service — title={}", title);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );

            long elapsed = System.currentTimeMillis() - startTime;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("[PDF Export] Conversion service returned non-2xx — status={}, elapsedMs={}",
                        response.getStatusCode(), elapsed);
                throw new BusinessException(ApiCode.INTERNAL_ERROR,
                        "PDF conversion failed");
            }

            byte[] pdfBytes = response.getBody();
            log.info("[PDF Export] Success — pdfSize={}, elapsedMs={}",
                    pdfBytes.length, elapsed);

            return pdfBytes;
        } catch (BusinessException e) {
            log.error("[PDF Export] Conversion failed — elapsedMs={}, error={}",
                    System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[PDF Export] Conversion service request failed — elapsedMs={}, error={}",
                    System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF conversion failed");
        } finally {
            concurrencySemaphore.release();
        }
    }
}
