package com.studyagent.infra.service.billing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxApplicationDatabaseTest {
    @ParameterizedTest @ValueSource(strings={"jdbc:mysql://127.0.0.1:13306/payment_recovery_test_native?useSSL=false&serverTimezone=Asia/Shanghai","jdbc:mysql://localhost:13306/payment_recovery_test_20261116?serverTimezone=Asia/Shanghai"})
    void acceptsOnlyExplicitlyNamedLocalSchemas(String url){assertTrue(RecoverySandboxApplicationDatabase.requireLocalSchema(url).startsWith("payment_recovery_test_"));}
    @ParameterizedTest @ValueSource(strings={"jdbc:mysql://localhost:3306/payment_recovery_test_native?serverTimezone=Asia/Shanghai","jdbc:mysql://77.42.123.251:13306/studyagent?serverTimezone=Asia/Shanghai","jdbc:mysql://77.42.123.251:13306/payment_recovery_test_native?serverTimezone=Asia/Shanghai","jdbc:mysql://127.0.0.1:13306/studyagent?serverTimezone=Asia/Shanghai","jdbc:mysql://localhost:13306/payment_recovery_test_native","jdbc:mysql://localhost:13306/payment_recovery_test_native?serverTimezone=UTC","jdbc:mysql://example.com:13306/payment_recovery_test_native?serverTimezone=Asia/Shanghai","jdbc:mysql://localhost:13306/payment_recovery_test_native/other?serverTimezone=Asia/Shanghai"})
    void rejectsRemoteSharedOrAmbiguousTargetsBeforeConnecting(String url){assertThrows(IllegalArgumentException.class,()->RecoverySandboxApplicationDatabase.requireLocalSchema(url));}
    @Test void missingTargetCannotFallBackToTheApplicationDatabase(){assertThrows(IllegalArgumentException.class,()->RecoverySandboxApplicationDatabase.requireLocalSchema(null));}
}
