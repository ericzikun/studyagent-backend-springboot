package com.studyagent.infra.metrics;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.stripe.exception.StripeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Low-cardinality measurements for one outbound dependency attempt. */
@Component
public class ExternalDependencyMetrics {

    private final MeterRegistry meterRegistry;

    public ExternalDependencyMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Observation start() {
        return new Observation(Timer.start(meterRegistry));
    }

    public void success(Observation observation, Dependency dependency, Operation operation) {
        record(observation, dependency, operation, "success", "none");
    }

    public void error(Observation observation, Dependency dependency, Operation operation, Throwable error) {
        record(observation, dependency, operation, "error", errorType(dependency, error));
    }

    private void record(Observation observation, Dependency dependency, Operation operation,
                        String result, String errorType) {
        String[] tags = {
                "dependency", dependency.tag(),
                "operation", operation.tag(),
                "result", result,
                "error_type", errorType
        };
        Counter.builder("studyagent.external.requests").tags(tags).register(meterRegistry).increment();
        observation.sample().stop(Timer.builder("studyagent.external.request.duration")
                .tags(tags)
                .register(meterRegistry));
    }

    private static String errorType(Dependency dependency, Throwable error) {
        Throwable candidate = rootCause(error);
        if (candidate instanceof TimeoutException || candidate instanceof SocketTimeoutException) {
            return "timeout";
        }
        Integer status = responseStatus(candidate);
        if (status != null) {
            if (status == 429) {
                return dependency.tag() + "_429";
            }
            if (status >= 500) {
                return dependency.tag() + "_5xx";
            }
            if (status >= 400) {
                return dependency.tag() + "_4xx";
            }
        }
        if (candidate instanceof OSSException || candidate instanceof ClientException) {
            return "oss";
        }
        return "internal";
    }

    private static Integer responseStatus(Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().value();
        }
        if (error instanceof StripeException stripeException) {
            return stripeException.getStatusCode();
        }
        return null;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public record Observation(Timer.Sample sample) {
    }

    public enum Dependency {
        STRIPE,
        CLERK,
        OSS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Operation {
        CHECKOUT_CREATE,
        CHECKOUT_RETRIEVE,
        CHECKOUT_EXPIRE,
        SUBSCRIPTION_RETRIEVE,
        SUBSCRIPTION_UPDATE,
        REFUND,
        REMOTE_API,
        SIGN,
        PUT,
        HEAD,
        FINALIZE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
