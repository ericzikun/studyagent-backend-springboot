package com.studyagent.start.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EnvConfigTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadEnvFileSupportsShellExportSyntax() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, String.join(System.lineSeparator(),
                "export STRIPE_SECRET_KEY=sk_test_real",
                "export STRIPE_PUBLISHABLE_KEY=\"pk_test_real\"",
                "BILLING_CHECKOUT_MOCK_ENABLED=false",
                "# ignored comment"));

        Map<String, Object> properties = new EnvConfig().loadEnvFile(envFile.toFile());

        assertEquals("sk_test_real", properties.get("STRIPE_SECRET_KEY"));
        assertEquals("pk_test_real", properties.get("STRIPE_PUBLISHABLE_KEY"));
        assertEquals("false", properties.get("BILLING_CHECKOUT_MOCK_ENABLED"));
        assertFalse(properties.containsKey("export STRIPE_SECRET_KEY"));
    }

    @Test
    void loadEnvFilesLetsLocalOverrideSharedEnv() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Path localEnvFile = tempDir.resolve(".env.local");
        Files.writeString(envFile, String.join(System.lineSeparator(),
                "STRIPE_WEBHOOK_SECRET=whsec_shared",
                "PAYMENT_CHECKOUT_MOCK_ENABLED=true"));
        Files.writeString(localEnvFile, String.join(System.lineSeparator(),
                "STRIPE_WEBHOOK_SECRET=whsec_local",
                "BILLING_CHECKOUT_MOCK_ENABLED=false"));

        Map<String, Object> properties = new EnvConfig()
                .loadEnvFiles(List.of(envFile.toFile(), localEnvFile.toFile()));

        assertEquals("whsec_local", properties.get("STRIPE_WEBHOOK_SECRET"));
        assertEquals("true", properties.get("PAYMENT_CHECKOUT_MOCK_ENABLED"));
        assertEquals("false", properties.get("BILLING_CHECKOUT_MOCK_ENABLED"));
    }
}
