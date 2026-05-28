package com.studyagent.service.application.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VerlaEditorPdfExportService {

    private final RestTemplate restTemplate;
    private final String gotenbergBaseUrl;
    private final Semaphore concurrencySemaphore;

    public VerlaEditorPdfExportService(
            @Value("${gotenberg.base-url:}") String gotenbergBaseUrl,
            @Value("${verla.editor.pdf-export.max-concurrent:2}") int maxConcurrent) {

        if (gotenbergBaseUrl == null || gotenbergBaseUrl.isBlank()) {
            log.error("[PDF Export] gotenberg.base-url is not configured — PDF export will fail");
        }

        this.gotenbergBaseUrl = (gotenbergBaseUrl != null && gotenbergBaseUrl.endsWith("/"))
                ? gotenbergBaseUrl.substring(0, gotenbergBaseUrl.length() - 1)
                : gotenbergBaseUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);

        this.concurrencySemaphore = new Semaphore(Math.max(1, maxConcurrent));
        log.info("[PDF Export] Initialized — baseUrl={}, maxConcurrent={}",
                this.gotenbergBaseUrl, maxConcurrent);
    }

    public byte[] convertDocxToPdf(byte[] docxBytes, long docxSize) {
        long startTime = System.currentTimeMillis();

        if (gotenbergBaseUrl == null || gotenbergBaseUrl.isBlank()) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF export service is not configured");
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
            String url = gotenbergBaseUrl + "/forms/libreoffice/convert";

            ByteArrayResource fileResource = new ByteArrayResource(docxBytes) {
                @Override
                public String getFilename() {
                    return "input.docx";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("files", fileResource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity =
                    new HttpEntity<>(body, headers);

            log.info("[PDF Export] Sending to Gotenberg — docxSize={} bytes", docxSize);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );

            long elapsed = System.currentTimeMillis() - startTime;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                int pdfSize = response.getBody() != null ? response.getBody().length : 0;
                log.error("[PDF Export] Gotenberg returned non-2xx — status={}, pdfSize={}, elapsedMs={}",
                        response.getStatusCode(), pdfSize, elapsed);
                throw new BusinessException(ApiCode.INTERNAL_ERROR,
                        "PDF conversion failed");
            }

            byte[] pdfBytes = response.getBody();
            log.info("[PDF Export] Success — docxSize={}, pdfSize={}, elapsedMs={}",
                    docxSize, pdfBytes.length, elapsed);

            return pdfBytes;
        } catch (BusinessException e) {
            log.error("[PDF Export] Conversion failed — docxSize={}, elapsedMs={}, error={}",
                    docxSize, System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[PDF Export] Gotenberg request failed — docxSize={}, elapsedMs={}, error={}",
                    docxSize, System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "PDF conversion failed");
        } finally {
            concurrencySemaphore.release();
        }
    }
}
