package com.studyagent.infra.service.billing;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The TEST-only shared filesystem lock is acquired before recovery claims or transactions. */
@Aspect @Component
@ConditionalOnProperty(name="billing.payment-recovery.test-clock.enabled",havingValue="true")
public class RecoveryTestClockCoordination {
    private final RecoveryTestClockSupport support;
    public RecoveryTestClockCoordination(RecoveryTestClockSupport support){this.support=support;}
    @Around("execution(public * com.studyagent.infra.service.billing.SubscriptionPaymentRecoveryService.*(..))")
    public Object coordinate(ProceedingJoinPoint call){return support.locked(()->{
        try{return call.proceed();}catch(RuntimeException|Error e){throw e;}catch(Throwable e){throw new IllegalStateException(e);}
    });}
}
