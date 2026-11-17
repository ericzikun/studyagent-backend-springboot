package com.studyagent.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import com.studyagent.infra.service.billing.PaymentRecoveryProperties;
import com.studyagent.service.domain.user.ClerkClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Synchronous provider acknowledgement; caller persists the frozen body and outcome under its action lease. */
@Component
public class BillingPaymentRecoveryEmailSender {
    public record SendResult(String state,String messageId,String error){}
    private final ClerkClient clerk;
    /** Two WebClient beans exist (webClient, humanizerWebClient); the qualifier is mandatory. */
    private final WebClient webClient;
    private final PaymentRecoveryProperties config;
    @Value("${notification.email.resend-api-key:}") private String apiKey;
    private static final ObjectMapper JSON=new ObjectMapper();

    public BillingPaymentRecoveryEmailSender(ClerkClient clerk,
            @Qualifier("webClient") WebClient webClient, PaymentRecoveryProperties config) {
        this.clerk = clerk;
        this.webClient = webClient;
        this.config = config;
    }

    public static boolean eligible(SubscriptionPaymentRecoveryEntity row){
        return row!=null&&row.getOwnerRecoveryId()==null&&"OPEN_MANUAL".equals(row.getStatus())
                &&!"insufficient_funds".equals(row.getLatestDeclineCode())&&row.getAmountPaidMinor()!=null&&row.getAmountPaidMinor()==0
                &&row.getAmountRemainingMinor()!=null&&row.getAmountRemainingMinor()>0&&row.getAccountClosedAt()==null
                &&row.getDeadlineAt()!=null&&row.getDeadlineAt().isAfter(DateTimeFormats.now());
    }
    public String prepare(SubscriptionPaymentRecoveryEntity row,String url){
        outsideTransaction();if(!eligible(row))throw new IllegalStateException("Recovery no longer qualifies for a reminder");
        String to=clerk.getUserEmail(row.getClerkUserId());
        if(to==null||to.isBlank()||config.getEmailFrom()==null||config.getEmailFrom().isBlank())throw new IllegalStateException("Recovery mail sender or recipient unavailable");
        String currency=row.getCurrency().toUpperCase(Locale.ROOT);
        int digits=Currency.getInstance(currency).getDefaultFractionDigits();
        // Current enabled catalog currencies use ISO payment minor units; unverified currencies cannot enter cohorts.
        String amount=BigDecimal.valueOf(row.getAmountRemainingMinor(),digits).toPlainString()+" "+currency;
        String text="Your subscription payment is pending ("+amount+"). Check your bank's notice, complete verification, or update your payment method in your account.\n"
                +"Payment window ends: "+DateTimeFormats.formatApi(row.getDeadlineAt())+".\n"+url;
        try{return JSON.writeValueAsString(Map.of("from",config.getEmailFrom(),"to",List.of(to),"subject","Subscription payment pending","text",text));}
        catch(Exception e){throw new IllegalArgumentException("Unable to freeze recovery mail",e);}
    }
    public SendResult send(String frozen,String key){
        outsideTransaction();if(apiKey==null||apiKey.isBlank())return new SendResult("FAILED",null,"Resend is not configured");
        try{
            Map<?,?> result=webClient.post().uri("https://api.resend.com/emails").header("Authorization","Bearer "+apiKey).header("Content-Type","application/json")
                    .header("Idempotency-Key",key).bodyValue(frozen).retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(10));
            Object id=result==null?null:result.get("id");
            return id instanceof String value&&!value.isBlank()?new SendResult("SUCCEEDED",value,null):new SendResult("UNKNOWN",null,"Provider acknowledgement missing message id");
        }catch(WebClientResponseException e){return new SendResult(e.getStatusCode().is4xxClientError()?"FAILED":"UNKNOWN",null,"HTTP_"+e.getStatusCode().value());}
        catch(RuntimeException e){return new SendResult("UNKNOWN",null,e.getClass().getSimpleName());}
    }
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery mail I/O cannot run in a database transaction");}
}
