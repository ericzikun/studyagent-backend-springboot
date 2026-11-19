package com.studyagent.infra.service.billing;

import com.studyagent.api.controller.internal.RecoveryTestClockController;
import com.studyagent.api.filter.RecoveryTestClockOperatorFilter;
import com.studyagent.infra.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecoveryTestClockWiringTest {
    @TempDir Path directory;
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Config.class,
            RecoveryTestClockProperties.class,RecoveryTestClockStripe.class,RecoveryTestClockSupport.class,
            RecoveryTestClockCoordination.class,SubscriptionRecoveryStripeGateway.class,
            RecoveryTestClockController.class,RecoveryTestClockOperatorFilter.class);
    @Test void defaultDeploymentHasNoOperatorOrClockCoordinator(){runner.run(context->{
        assertThat(context).hasNotFailed().doesNotHaveBean(RecoveryTestClockSupport.class).doesNotHaveBean(RecoveryTestClockController.class).doesNotHaveBean(RecoveryTestClockOperatorFilter.class).doesNotHaveBean(RecoveryTestClockCoordination.class);
        assertThat(context.getBean(SubscriptionRecoveryStripeGateway.class)).isNotNull();
    });}
    @Test void explicitTestConfigurationWiresTheWholeOperator(){enabled().withInitializer(c->c.getEnvironment().setActiveProfiles("recovery-test-clock")).run(context->{
        assertThat(context).hasNotFailed().hasSingleBean(RecoveryTestClockSupport.class).hasSingleBean(RecoveryTestClockController.class).hasSingleBean(RecoveryTestClockOperatorFilter.class).hasSingleBean(RecoveryTestClockCoordination.class);
    });}
    @Test void propertyAloneCannotEnableAnOrdinaryDeployment(){enabled().run(context->assertThat(context).hasFailed());}
    @Test void liveKeyCannotStartEvenWithExplicitProfile(){enabled().withPropertyValues("stripe.secret-key=sk_live_rejected").withInitializer(c->c.getEnvironment().setActiveProfiles("recovery-test-clock")).run(context->assertThat(context).hasFailed());}
    private ApplicationContextRunner enabled(){return runner.withPropertyValues("billing.payment-recovery.test-clock.enabled=true","billing.payment-recovery.test-clock.directory="+directory,
            "billing.payment-recovery.test-clock.operator-token=local-test-operator-token-at-least-thirty-two","billing.payment-recovery.test-clock.allowed-user-ids=user_owned","stripe.secret-key=sk_test_isolated");}
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties @EnableAspectJAutoProxy
    static class Config{
        @Bean UserSubscriptionMapper users(){return mock(UserSubscriptionMapper.class);}
        @Bean SubscriptionPaymentRecoveryMapper roots(){return mock(SubscriptionPaymentRecoveryMapper.class);}
        @Bean PlatformTransactionManager transactions(){return mock(PlatformTransactionManager.class);}
    }
}
