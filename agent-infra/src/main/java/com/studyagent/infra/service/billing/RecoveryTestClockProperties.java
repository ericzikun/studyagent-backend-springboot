package com.studyagent.infra.service.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.nio.file.Path;
import java.util.Arrays;

/** Deliberately separate from production recovery admission and release cohorts. */
@Getter @Setter
@Component
@ConfigurationProperties("billing.payment-recovery.test-clock")
public class RecoveryTestClockProperties {
    private boolean enabled;
    private String directory = "";
    private String operatorToken = "";
    private Set<String> allowedUserIds = Set.of();

    void validate(Environment environment,String stripeKey) {
        var profiles=Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if(!enabled || !profiles.contains("recovery-test-clock") || profiles.stream().anyMatch(p->Set.of("prod","production","live").contains(p.toLowerCase()))
                || stripeKey==null || !(stripeKey.startsWith("sk_test_")||stripeKey.startsWith("rk_test_"))
                || operatorToken==null || operatorToken.length()<32 || operatorToken.length()>256 || directory==null || directory.isBlank() || !Path.of(directory).isAbsolute()
                || allowedUserIds==null || allowedUserIds.isEmpty() || allowedUserIds.stream().anyMatch(id->id==null||!id.startsWith("user_")||id.contains("*")))
            throw new IllegalStateException("RECOVERY_TEST_CLOCK_UNSAFE_CONFIGURATION");
    }
}
