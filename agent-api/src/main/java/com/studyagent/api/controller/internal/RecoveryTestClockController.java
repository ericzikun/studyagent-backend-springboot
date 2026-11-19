package com.studyagent.api.controller.internal;

import com.studyagent.infra.service.billing.RecoveryTestClockSupport;
import com.studyagent.api.filter.RecoveryTestClockOperatorFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.web.server.ResponseStatusException;

/** TEST operator commands, protected by RecoveryTestClockOperatorFilter. */
@RestController
@RequestMapping("/v1/internal/billing/recovery-test-clocks")
@ConditionalOnProperty(name="billing.payment-recovery.test-clock.enabled",havingValue="true")
public class RecoveryTestClockController {
    private final RecoveryTestClockSupport support;
    private final RecoveryTestClockOperatorFilter authorization;
    public RecoveryTestClockController(RecoveryTestClockSupport support,RecoveryTestClockOperatorFilter authorization){this.support=support;this.authorization=authorization;}
    @ModelAttribute
    public void authorizeOperator(HttpServletRequest request){authorization.requireOperator(request);}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> authorizationDenied(ResponseStatusException denied){return ResponseEntity.status(denied.getStatusCode()).build();}
    public record Register(String runId,String userId,String customerId,String clockId){}
    public record Pause(String subscriptionId){}
    public record Advance(String checkpoint){}
    @PostMapping("/runs") public ResponseEntity<?> register(@RequestBody Register body){return call(()->support.register(body.runId(),body.userId(),body.customerId(),body.clockId()));}
    @PostMapping("/runs/{run}/pause") public ResponseEntity<?> pause(@PathVariable String run,@RequestBody Pause body){return call(()->support.pause(run,body.subscriptionId()));}
    @PostMapping("/runs/{run}/first-failure") public ResponseEntity<?> firstFailure(@PathVariable String run){return call(()->support.firstPay(run));}
    @PostMapping("/runs/{run}/advance") public ResponseEntity<?> advance(@PathVariable String run,@RequestBody Advance body){return call(()->support.advance(run,body.checkpoint()));}
    @GetMapping("/runs/{run}") public ResponseEntity<?> status(@PathVariable String run){return call(()->support.status(run));}
    private static ResponseEntity<?> call(Callable<Map<String,Object>> call){
        try{return ResponseEntity.ok(call.call());}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error","INVALID_TEST_OPERATION"));}
        catch(Exception e){
            String reason=e.getMessage();if(reason==null||!reason.matches("RECOVERY_TEST_CLOCK_[A-Z_]+"))reason=e.getClass().getSimpleName();
            return ResponseEntity.status(409).body(Map.of("error","TEST_OPERATION_REQUIRES_RECONCILIATION","reason",reason));
        }
    }
}
