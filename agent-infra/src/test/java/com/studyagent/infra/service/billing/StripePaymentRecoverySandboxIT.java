package com.studyagent.infra.service.billing;

import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionUpdateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Creates isolated test objects; never consumes application customers/prices. */
@EnabledIfEnvironmentVariable(named = "STRIPE_RECOVERY_SANDBOX", matches = "true")
class StripePaymentRecoverySandboxIT {
    private final String run = "recovery-" + UUID.randomUUID();
    private final List<String> clocks = new ArrayList<>();
    private final List<String> prices = new ArrayList<>();
    private final List<String> products = new ArrayList<>();
    private final RecoverySandboxCreationTracker creations = new RecoverySandboxCreationTracker(run, this::record);
    private RequestOptions options;
    private Path journal;
    private int sequence;
    private String stage = "SETUP";
    private String failureFixture;
    private RecoverySandboxApplicationHarness application;
    private boolean applicationCleanupFailed;
    private final Map<String,String> fundsSetupCustomers=new LinkedHashMap<>();
    private final RecoveryOwnedClockEvidence.Registry ownedClockEvidence=new RecoveryOwnedClockEvidence.Registry();
    private record ControlledFailure(RecoveryOwnedClockEvidence.Validated proof,Event event){}
    private final boolean atomicCandidate = "true".equals(System.getenv("STRIPE_RECOVERY_ATOMIC_TRIAL_END"));
    private final boolean d30Closure = "true".equals(System.getenv("STRIPE_RECOVERY_D30_CLOSE"));
    private final boolean precloseOwnedIntroDraft = "true".equals(System.getenv("STRIPE_RECOVERY_PRECLOSE_SETUP_DRAFT"));
    private final boolean activeCancel = "true".equals(System.getenv("STRIPE_RECOVERY_ACTIVE_CANCEL"));
    private final boolean annualRefresh = "true".equals(System.getenv("STRIPE_RECOVERY_ANNUAL_REFRESH"));
    private final boolean fundsSetup = "true".equals(System.getenv("STRIPE_RECOVERY_FUNDS_SETUP"));
    private final boolean mailAcceptance = "true".equals(System.getenv("STRIPE_RECOVERY_MAIL_ACCEPTANCE"));
    private final boolean shiftAckLoss = "true".equals(System.getenv("STRIPE_RECOVERY_SHIFT_ACK_LOSS"));
    private final boolean portalHosted = "true".equals(System.getenv("STRIPE_RECOVERY_PORTAL_HOSTED"));
    private final boolean replacementCheckout = "true".equals(System.getenv("STRIPE_RECOVERY_REPLACEMENT"));
    private final boolean replacementDouble = "true".equals(System.getenv("STRIPE_RECOVERY_REPLACEMENT_DOUBLE"));
    private final List<String> portalConfigurations = new ArrayList<>();

    @Test
    void verifiesProtectedPaymentAndTrialEndCandidate() throws Exception {
        String key = System.getenv("STRIPE_RECOVERY_TEST_SECRET_KEY");
        assertNotNull(key, "Dedicated STRIPE_RECOVERY_TEST_SECRET_KEY is required");
        assertTrue(key.startsWith("sk_test_") || key.startsWith("rk_test_"), "Test key required");
        assertEquals("2023-10-16", Stripe.API_VERSION);
        failureFixture=System.getenv().getOrDefault("STRIPE_RECOVERY_FAILURE_FIXTURE","generic_decline");
        assertTrue(Set.of("generic_decline","insufficient_funds").contains(failureFixture),"Explicit supported failure fixture required before remote actions");
        options = RequestOptions.builder().setApiKey(key).build();
        journal = Path.of(System.getProperty("recovery.evidence.dir", "target/payment-recovery-evidence"), run + ".jsonl");
        Files.createDirectories(journal.getParent());
        record("run", Map.of("runId", run, "sdk", Stripe.VERSION, "apiVersion", Stripe.API_VERSION, "candidate", atomicCandidate ? "ATOMIC_TRIAL_END_RELEASE" : "KEEP_PAUSED_THEN_TRIAL_END",
                "mailResult", "NOT_RUN", "portalResult", "NOT_RUN", "hostedPageResult", "NOT_RUN", "failureFixture", failureFixture, "insufficientFundsLiveFixture", "NOT_RUN", "validationScope", "true".equals(System.getenv("STRIPE_RECOVERY_APPLICATION_IT"))?(replacementDouble?"APPLICATION_INBOX_SQL_REPLACEMENT_DOUBLE_PAYMENT":replacementCheckout?"APPLICATION_INBOX_SQL_REPLACEMENT":portalHosted?"APPLICATION_INBOX_SQL_PORTAL_HOSTED":mailAcceptance?"APPLICATION_INBOX_SQL_MAIL_PROVIDER_ACK":shiftAckLoss?"APPLICATION_INBOX_SQL_SHIFT_ACK_LOSS":annualRefresh?"APPLICATION_INBOX_SQL_ANNUAL_REFRESH":activeCancel?"APPLICATION_INBOX_SQL_ACTIVE_CANCEL":d30Closure?(fundsSetup?"APPLICATION_INBOX_SQL_FUNDS_D30_CLOSE":"APPLICATION_INBOX_SQL_D30_CLOSE"):fundsSetup?"APPLICATION_INBOX_SQL_FUNDS_WORKER":precloseOwnedIntroDraft?"APPLICATION_INBOX_SQL_PRECLOSED_SETUP_DRAFT":"APPLICATION_INBOX_SQL"):d30Closure?"API_D30_CLOSE":"API_ONLY"));
        // A single exact key per invocation gives failures an unambiguous owner.
        String caseKey = System.getenv().getOrDefault("STRIPE_RECOVERY_CASE_KEY",
                "formal:pro_monthly:month_1:NO_SCHEDULE:RENEW");
        String fixturePath=System.getenv("STRIPE_RECOVERY_FIXTURE_FILE");
        if(fixturePath==null)assertTrue(caseKey.matches("(formal:pro_(monthly:month|yearly:year)_1:NO_SCHEDULE|pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE):(RENEW|PERIOD_END)"),"A validated explicit recipe is required for additional contract shapes");
        var fixture=RecoverySandboxFixture.parse(caseKey,fixturePath==null?"{\"currency\":\"usd\",\"unitAmountMinor\":2900,\"introAmountMinor\":299}":Files.readString(Path.of(fixturePath)));
        if(portalHosted)RecoverySandboxPortalHosted.requireScope(fixture,System.getenv(),failureFixture);
        if(replacementDouble)RecoverySandboxReplacement.requireDoubleScope(fixture,System.getenv(),failureFixture);
        if(replacementCheckout){
            RecoverySandboxReplacement.requireScope(fixture,System.getenv(),failureFixture);
            record("replacement-acceptance-scope",Map.of("validationScope",replacementDouble?"APPLICATION_INBOX_SQL_REPLACEMENT_DOUBLE_PAYMENT":"APPLICATION_INBOX_SQL_REPLACEMENT",
                    "checkoutPageResult","NOT_RUN","httpResult","NOT_RUN","clerkResult","NOT_RUN",
                    "quartzResult","NOT_RUN","trustedQuoteHttpResult","NOT_RUN","automaticRetrySlotsResult","NOT_RUN"));
        }
        if(mailAcceptance){
            assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"));
            assertTrue(fixture.priceUpdate());assertEquals("month",fixture.interval());assertFalse(fixture.keepSchedule());assertFalse(fixture.periodEnd());
            assertFalse(fundsSetup);assertFalse(d30Closure);assertFalse(annualRefresh);assertFalse(activeCancel);assertFalse(precloseOwnedIntroDraft);assertFalse(shiftAckLoss);assertFalse(atomicCandidate);
            assertEquals("generic_decline",failureFixture);
            assertTrue("Verla <notifications@send.verla.io>".equals(System.getenv("STRIPE_RECOVERY_MAIL_FROM")),"Explicit verified sender is required before remote fixture creation");
            assertTrue(System.getenv("STRIPE_RECOVERY_MAIL_TO")!=null&&!System.getenv("STRIPE_RECOVERY_MAIL_TO").isBlank(),"Owned private recipient is required");
            assertTrue(System.getenv("STRIPE_RECOVERY_MAIL_API_KEY")!=null&&!System.getenv("STRIPE_RECOVERY_MAIL_API_KEY").isBlank(),"Private mail API key is required");
        }
        if(shiftAckLoss){
            assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"));
            assertTrue(fixture.priceUpdate());assertEquals("month",fixture.interval());assertFalse(fixture.keepSchedule());assertFalse(fixture.periodEnd());
            assertFalse(fundsSetup);assertFalse(d30Closure);assertFalse(annualRefresh);assertFalse(activeCancel);assertFalse(precloseOwnedIntroDraft);
            assertEquals("generic_decline",failureFixture);
            record("shift-ack-loss-scenario",Map.of("injection","TEST_AFTER_NATIVE_SUCCESS","networkDrop",false,"processKill",false,"scope","APPLICATION_INBOX_SQL_MONTHLY_PRICE_UPDATE_RENEW"));
        }
        if(annualRefresh){
            assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"));assertTrue(key.startsWith("sk_test_"),"Production quota simulation resolver requires sk_test_ key");
            assertEquals("year",fixture.interval());assertTrue(fixture.priceUpdate());assertTrue(fixture.periodEnd());assertFalse(fixture.keepSchedule());
            assertFalse(fundsSetup);assertFalse(d30Closure);assertFalse(activeCancel);assertFalse(precloseOwnedIntroDraft);assertEquals("generic_decline",failureFixture);
        }
        if(fundsSetup){
            options=options.toBuilder().setMaxNetworkRetries(0).build();
            assertEquals("insufficient_funds",failureFixture,"Saved funds fixture requires the explicit funds scenario");
            assertTrue((fixture.priceUpdate()||fixture.explicitFirstPay())&&!fixture.periodEnd(),"Saved funds probe requires a controlled renewing fixture");
            if(d30Closure)assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"),"Funds D30 requires the real isolated application worker");assertFalse(precloseOwnedIntroDraft,"Funds worker scenario uses the original native setup drafts");
        }
        if("true".equals(System.getenv("STRIPE_RECOVERY_APPLICATION_IT"))){
            if(d30Closure){assertEquals(fundsSetup?"insufficient_funds":"generic_decline",failureFixture);assertFalse(precloseOwnedIntroDraft,"Application D30 must retain the original owned trial draft");}
            assertTrue(fixture.priceUpdate()||fixture.explicitFirstPay(),"Application wiring requires an owned controlled first failure");
            assertTrue("generic_decline".equals(failureFixture)||fundsSetup&&"insufficient_funds".equals(failureFixture),"Application funds requires explicit saved-method setup");
            RecoverySandboxApplicationDatabase.requireLocalSchema(System.getenv("PAYMENT_RECOVERY_TEST_JDBC_URL"));
        }
        if(activeCancel){
            assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"),"Active cancellation requires the isolated application inbox");
            assertEquals("generic_decline",failureFixture);assertFalse(fundsSetup);assertFalse(d30Closure);assertFalse(precloseOwnedIntroDraft);assertFalse(fixture.periodEnd());assertTrue(fixture.intro());
        }
        if(d30Closure)assertTrue(fixture.priceUpdate()&&!fixture.periodEnd(),"D30 probe requires proven owned first failure and a renewing contract");
        if(precloseOwnedIntroDraft)assertTrue("true".equals(System.getenv("STRIPE_RECOVERY_APPLICATION_IT"))&&fixture.priceUpdate(),"Setup-draft control is restricted to the isolated application fixture");
        var fixtureEvidence=new LinkedHashMap<String,Object>();fixtureEvidence.put("caseKey",caseKey);fixtureEvidence.put("conversionMode",fixture.conversionMode());fixtureEvidence.put("currency",fixture.currency());fixtureEvidence.put("unitAmountMinor",fixture.unitAmountMinor());fixtureEvidence.put("introAmountMinor",fixture.introAmountMinor());fixtureEvidence.put("sourcePriceId",fixture.sourcePriceId());fixtureEvidence.put("priceVersion",fixture.priceVersion());fixtureEvidence.put("basis",fixturePath==null?"SYNTHETIC_MECHANISM":"OWNED_TEST_CLONE_OF_SCALAR_CONTRACT");fixtureEvidence.put("finalResult","NOT_RUN");
        record("case", fixtureEvidence);
        try {
            execute(fixture);
            record("apiResult", Map.of("caseKey", caseKey, "apiResult", application==null?"PASS":"NOT_RUN", "combinationResult", application==null&&!d30Closure?"PASS":"NOT_RUN",
                    "applicationServiceResult",application==null?"NOT_RUN":"PASS",
                    "finalResult", "NOT_RUN", "limitation", "Hosted Invoice page, Portal and production mail evidence required"));
        } catch (Exception | AssertionError e) {
            record("apiResult", Map.of("caseKey", caseKey, "apiResult", "FAIL", "finalResult", "FAIL",
                    "errorType", e.getClass().getSimpleName(), "message", safeMessage(e), "stage", stage));
            throw e;
        } finally {
            try{if(application!=null)application.close();}
            catch(Exception|AssertionError failure){
                if(replacementCheckout){applicationCleanupFailed=true;record("apiResult",Map.of("caseKey",caseKey,"applicationServiceResult","FAIL","apiResult","FAIL","finalResult","FAIL","stage","APPLICATION_REPLACEMENT_CLEANUP","errorType",failure.getClass().getSimpleName()));}
                throw failure;
            }
            finally{cleanup();}
        }
    }

    private void execute(RecoverySandboxFixture fixture) throws Exception {
        String caseKey=fixture.caseKey(),interval=fixture.interval();boolean intro=fixture.intro(),periodEnd=fixture.periodEnd();
        TestClock beforeOperationClock=null;Subscription beforeOperationSub=null;String operationRequestId=null;Instant operationRealStart=null;
        Set<String> beforeInvoiceIds=new HashSet<>(),beforeFormalInvoiceIds=new HashSet<>(),usedPriceIds=new HashSet<>();
        ControlledFailure controlledFailure=null;
        RecoveryOwnedClockEvidence.PreAttempt beforeExplicitPay=null;
        long now = System.getenv("STRIPE_RECOVERY_CLOCK_START") == null ? Instant.now().getEpochSecond()
                : Instant.parse(System.getenv("STRIPE_RECOVERY_CLOCK_START")).getEpochSecond();
        TestClock clock = creations.create("create-clock", Map.of("frozen_time", now, "name", run),
                (parameters, key) -> TestClock.create(parameters, createOptions(key)), created -> {
                    assertEquals(Boolean.FALSE, created.getLivemode()); assertEquals(run, created.getName());
                    clocks.add(created.getId());
                    record("clock", Map.of("id", created.getId(), "frozenTime", now));
                });
        Product product = creations.create("create-product", Map.of("name", run, "metadata", metadata(caseKey)),
                (parameters, key) -> Product.create(parameters, createOptions(key)), created -> {
                    owned(created.getLivemode(), created.getMetadata()); products.add(created.getId());
                    record("created-product", Map.of("id", created.getId()));
                });
        Price price = creations.create("create-price", Map.of("product", product.getId(), "currency", fixture.currency(), "unit_amount", fixture.unitAmountMinor(),
                "recurring", Map.of("interval", interval), "metadata", metadata(caseKey)),
                (parameters, key) -> Price.create(parameters, createOptions(key)), created -> {
                    owned(created.getLivemode(), created.getMetadata()); assertEquals(product.getId(), created.getProduct());
                    prices.add(created.getId());
                    record("created-price", Map.of("id", created.getId(), "interval", interval, "currency", fixture.currency(), "unitAmount", fixture.unitAmountMinor()));
                });
        Customer customer = creations.create("create-customer", Map.of("name", run, "test_clock", clock.getId(), "metadata", metadata(caseKey)),
                (parameters, key) -> Customer.create(parameters, createOptions(key)), created -> {
                    owned(created.getLivemode(), created.getMetadata()); assertEquals(clock.getId(), created.getTestClock());
                    assertTrue(clocks.contains(clock.getId()), "Customer cleanup must be covered by a known owned clock");
                    record("created-customer", Map.of("id", created.getId(), "clockId", clock.getId()));
                });
        PaymentMethod good = PaymentMethod.retrieve("pm_card_visa", options)
                .attach(Map.of("customer", customer.getId()), write("attach-good"));
        assertEquals(customer.getId(), good.getCustomer());
        customer.update(Map.of("invoice_settings", Map.of("default_payment_method", good.getId())), write("default-good"));
        Subscription subscription;
        if (intro) {
            Price introPrice = creations.create("create-intro-price", Map.of("product", product.getId(), "currency", fixture.currency(), "unit_amount", fixture.introAmountMinor(),
                    "recurring", Map.of("interval", "week"), "metadata", metadata(caseKey)),
                    (parameters, key) -> Price.create(parameters, createOptions(key)), created -> {
                        owned(created.getLivemode(), created.getMetadata()); assertEquals(product.getId(), created.getProduct());
                        prices.add(created.getId()); record("created-intro-price", Map.of("id", created.getId()));
                    });
            if(fixture.priceUpdate()){
                subscription = creations.create("create-legacy-intro-subscription", Map.of("customer", customer.getId(),
                        "items", List.of(Map.of("price", introPrice.getId())), "metadata", metadata(caseKey)),
                        (parameters, key) -> Subscription.create(parameters, createOptions(key)),
                        created -> registerClockSubscription(created, customer.getId(), clock.getId()));
            }else{
                SubscriptionSchedule schedule = creations.create("create-schedule", Map.of("customer", customer.getId(),
                        "start_date", now, "end_behavior", "release", "metadata", metadata(caseKey),
                        "phases", List.of(Map.of("items", List.of(Map.of("price", introPrice.getId())), "iterations", 1),
                                Map.of("items", List.of(Map.of("price", price.getId())), "iterations", 2))),
                        (parameters, key) -> SubscriptionSchedule.create(parameters, createOptions(key)), created -> {
                            registerClockSchedule(created, customer.getId(), clock.getId());
                            record("created-schedule", Map.of("id", created.getId(), "phases", created.getPhases(), "endBehavior", created.getEndBehavior()));
                        });
                subscription = Subscription.retrieve(schedule.getSubscription(), options);
            }
        } else if(fixture.keepSchedule()){
            SubscriptionSchedule schedule = creations.create("create-formal-equivalent-schedule", Map.of("customer", customer.getId(),
                    "start_date", now, "end_behavior", "release", "metadata", metadata(caseKey),
                    "phases", List.of(Map.of("items", List.of(Map.of("price", price.getId())), "iterations", 2))),
                    (parameters, key) -> SubscriptionSchedule.create(parameters, createOptions(key)), created -> {
                        registerClockSchedule(created, customer.getId(), clock.getId());
                        record("created-formal-schedule", Map.of("id", created.getId()));
                    });
            subscription = Subscription.retrieve(schedule.getSubscription(),options);
        } else {
            subscription = creations.create("create-subscription", Map.of("customer", customer.getId(),
                    "items", List.of(Map.of("price", price.getId())), "metadata", metadata(caseKey)),
                    (parameters, key) -> Subscription.create(parameters, createOptions(key)),
                    created -> registerClockSubscription(created, customer.getId(), clock.getId()));
        }
        snapshot("initial", subscription);
        Invoice initialInvoice = Invoice.retrieve(subscription.getLatestInvoice(), options);
        if ("draft".equals(initialInvoice.getStatus())) initialInvoice = initialInvoice.finalizeInvoice(Map.of("auto_advance", false), write("finalize-initial"));
        if (!"paid".equals(initialInvoice.getStatus())) initialInvoice = initialInvoice.pay(Map.of("off_session", true), write("pay-initial"));
        assertEquals("paid", initialInvoice.getStatus(), "Initial trial purchase must be paid before testing conversion failure");
        record("initial-payment-confirmed", Map.of("invoiceId", initialInvoice.getId(), "status", initialInvoice.getStatus(), "amountPaid", initialInvoice.getAmountPaid()));
        stage = "BAD_METHOD_PREPARATION";
        PaymentMethod bad = fundsSetup?RecoverySandboxFundsSetup.prepare(run,customer,options,creations,this::record,
                setup->fundsSetupCustomers.put(setup.getId(),setup.getCustomer())):
                PaymentMethod.retrieve("insufficient_funds".equals(failureFixture)?"pm_card_chargeDeclinedInsufficientFunds":"pm_card_chargeCustomerFail", options)
                        .attach(Map.of("customer", customer.getId()), write("attach-bad"));
        assertEquals(customer.getId(), bad.getCustomer());
        subscription.update(Map.of("default_payment_method", bad.getId()), write("default-bad"));
        if(portalHosted){
            var body=Map.<String,Object>of("invoice_settings",Map.of("default_payment_method",bad.getId()));
            record("portal-fixture-default-bad-intent",Map.of("customerId",customer.getId(),"parameters",body,"idempotencyKey",run+":portal-default-bad"));
            var observed=customer.update(body,write("portal-default-bad"));
            assertEquals(bad.getId(),observed.getInvoiceSettings().getDefaultPaymentMethod());
        }
        stage = "FIRST_FAILURE";
        long oldBoundary = subscription.getCurrentPeriodEnd();
        if(fixture.priceUpdate()||fixture.explicitFirstPay())subscription=subscription.update(Map.of("pause_collection",Map.of("behavior","keep_as_draft")),write("fixture-pause-before-boundary"));
        Instant boundaryOperationStart=Instant.now().minusSeconds(1);
        advance(clock.getId(), oldBoundary);
        if(fixture.priceUpdate()){
            for(var old:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100),options).autoPagingIterable())if(Set.of("draft","open","uncollectible").contains(old.getStatus())){
                old.update(Map.of("auto_advance",false),write("fixture-protect-boundary-invoice-"+old.getId()));assertFalse(Invoice.retrieve(old.getId(),options).getAutoAdvance());
                if(precloseOwnedIntroDraft){
                    // Explicit test preparation, not a new production rule for recognizing old trial bills.
                    assertEquals(Boolean.FALSE,old.getLivemode());assertEquals(customer.getId(),old.getCustomer());assertEquals(subscription.getId(),old.getSubscription());assertEquals(0L,old.getAmountPaid());
                    assertEquals(1,old.getLines().getData().size());assertFalse(Boolean.TRUE.equals(old.getLines().getHasMore()));
                    var line=old.getLines().getData().get(0);assertEquals(initialInvoice.getLines().getData().get(0).getPrice().getId(),line.getPrice().getId());assertEquals(oldBoundary,line.getPeriod().getStart());assertEquals("week",line.getPrice().getRecurring().getInterval());
                    if("draft".equals(old.getStatus()))old=old.finalizeInvoice(SubscriptionRecoveryStripeGateway.finalizeParams(),write("fixture-preclose-finalize-"+old.getId()));
                    old=old.voidInvoice(Map.of(),write("fixture-preclose-void-"+old.getId()));assertEquals("void",old.getStatus());
                    record("fixture-preclosed-owned-intro-draft",Map.of("invoiceId",old.getId(),"status",old.getStatus(),"scope","TEST_SETUP_CONTROL_ONLY","productionTargetRecognition","UNCHANGED"));
                }
            }
            subscription=Subscription.retrieve(subscription.getId(),options);assertNull(subscription.getSchedule());
            beforeOperationSub=subscription;beforeOperationClock=TestClock.retrieve(clock.getId(),options);assertEquals("ready",beforeOperationClock.getStatus());assertEquals(oldBoundary,beforeOperationClock.getFrozenTime());assertEquals(run,beforeOperationClock.getName());
            for(var prior:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100),options).autoPagingIterable()){
                beforeInvoiceIds.add(prior.getId());assertEquals(Boolean.FALSE,prior.getLines().getHasMore(),"Complete preflight line list required");
                for(var line:prior.getLines().getData())if(line.getPrice()!=null){usedPriceIds.add(line.getPrice().getId());if(price.getId().equals(line.getPrice().getId()))beforeFormalInvoiceIds.add(prior.getId());}
            }
            assertTrue(beforeFormalInvoiceIds.isEmpty());assertFalse(usedPriceIds.contains(price.getId()));
            record("controlled-operation-baseline",Map.of("clockId",clock.getId(),"frozenTime",oldBoundary,"beforeInvoiceIds",beforeInvoiceIds,"beforeFormalInvoiceIds",beforeFormalInvoiceIds,"usedPriceIds",usedPriceIds));
            operationRealStart=Instant.now().minusSeconds(1);
            var conversion=new LinkedHashMap<String,Object>();conversion.put("items",List.of(Map.of("id",subscription.getItems().getData().get(0).getId(),"price",price.getId(),"quantity",1)));conversion.put("proration_behavior","none");conversion.put("pause_collection","");conversion.put("payment_behavior","allow_incomplete");
            // Saved-method funds fixture must construct an off-session first attempt too.
            // Subscription.update otherwise defaults to on-session; recovery Invoice.pay bodies stay unchanged.
            if(fundsSetup)conversion.put("off_session",true);
            if(periodEnd)conversion.put("cancel_at_period_end",true);
            subscription=subscription.update(conversion,write("fixture-convert-price-at-boundary"));
            operationRequestId=subscription.getLastResponse().requestId();
            record("legacy-price-updated",Map.of("subscriptionId",subscription.getId(),"boundary",oldBoundary,"sourceInvoice",initialInvoice.getId(),"prorationBehavior","none","offSession",fundsSetup));
        }
        if(fixture.explicitFirstPay()){
            subscription=Subscription.retrieve(subscription.getId(),options);assertNull(subscription.getSchedule());
            if(periodEnd)subscription=subscription.update(Map.of("cancel_at_period_end",true),write("fixture-formal-cancel-preference"));
            Invoice due=Invoice.retrieve(subscription.getLatestInvoice(),options);assertNotEquals(initialInvoice.getId(),due.getId());assertEquals("draft",due.getStatus());
            assertEquals("subscription_cycle",due.getBillingReason());assertEquals(Boolean.FALSE,due.getAutoAdvance());assertEquals(0L,due.getAmountPaid());
            due=due.finalizeInvoice(SubscriptionRecoveryStripeGateway.finalizeParams(),write("fixture-finalize-unattempted-formal"));
            beforeOperationClock=TestClock.retrieve(clock.getId(),options);beforeOperationSub=Subscription.retrieve(subscription.getId(),options);
            for(var prior:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100L),options).autoPagingIterable()){
                beforeInvoiceIds.add(prior.getId());assertEquals(Boolean.FALSE,prior.getLines().getHasMore());
                for(var line:prior.getLines().getData())if(line.getPrice()!=null){usedPriceIds.add(line.getPrice().getId());if(price.getId().equals(line.getPrice().getId()))beforeFormalInvoiceIds.add(prior.getId());}
            }
            owned(beforeOperationSub.getLivemode(),beforeOperationSub.getMetadata());subFact(beforeOperationSub);
            beforeExplicitPay=explicitPayPreflight(due);
            var body=Map.<String,Object>of("off_session",true);operationRealStart=boundaryOperationStart;
            record("formal-first-pay-intent",Map.of("invoiceId",due.getId(),"parameters",body,"idempotencyKey",run+":fixture-explicit-first-pay","before",beforeExplicitPay));
            try{due.pay(body,write("fixture-explicit-first-pay"));fail("The controlled formal first payment must really fail");}
            catch(CardException declined){assertEquals(failureFixture,declined.getDeclineCode());operationRequestId=declined.getRequestId();assertNotNull(operationRequestId);}
        }
        if(!fixture.priceUpdate()&&!fixture.explicitFirstPay())advance(clock.getId(), oldBoundary + 3600);
        subscription = Subscription.retrieve(subscription.getId(), options);
        Invoice invoice = Invoice.retrieve(subscription.getLatestInvoice(), options);
        assertEquals("open", invoice.getStatus());
        assertEquals(0L, invoice.getAmountPaid());
        if(fixture.priceUpdate()){
            assertEquals("subscription_update",invoice.getBillingReason());assertNull(subscription.getSchedule());
            assertEquals(oldBoundary,invoice.getLines().getData().get(0).getPeriod().getStart());assertEquals(oldBoundary,initialInvoice.getLines().getData().get(0).getPeriod().getEnd());
            assertFalse(Boolean.TRUE.equals(invoice.getLines().getData().get(0).getProration()));record("legacy-invoice-boundary-confirmed",Map.of("trialInvoiceId",initialInvoice.getId(),"formalInvoiceId",invoice.getId(),"billingReason",invoice.getBillingReason(),"boundary",oldBoundary));
        }
        assertEquals(price.getId(), invoice.getLines().getData().get(0).getPrice().getId());
        assertNotNull(invoice.getPaymentIntent());
        PaymentIntent pi = PaymentIntent.retrieve(invoice.getPaymentIntent(), options);
        assertNotNull(pi.getLastPaymentError(), "A real first failed payment is required");
        assertEquals(failureFixture, pi.getLastPaymentError().getDeclineCode());
        long failedAt = Charge.retrieve(pi.getLatestCharge(), options).getCreated();
        long first=oldBoundary+3600;
        if(fixture.priceUpdate()||fixture.explicitFirstPay()){
            controlledFailure=captureControlledFailure(clock.getId(),beforeOperationClock,beforeOperationSub,subscription,invoice,pi,operationRequestId,operationRealStart,beforeInvoiceIds,beforeFormalInvoiceIds,usedPriceIds,fixture.explicitFirstPay()?"EXPLICIT_FIRST_PAY":"PRICE_UPDATE",beforeExplicitPay);
            first=controlledFailure.proof().occurredAt().getEpochSecond();
            var proof=new RecoveryOwnedClockGateway(run,ownedClockEvidence).readFirstFailure(new SubscriptionRecoveryStripeGateway.Snapshot(invoice,subscription,null,invoice.getLines().getData(),pi,Charge.retrieve(pi.getLatestCharge(),options),Instant.ofEpochSecond(first)),controlledFailure.event());
            assertEquals(Instant.ofEpochSecond(first),proof.occurredAt());
        }
        var firstEvidence=new LinkedHashMap<String,Object>();firstEvidence.put("invoiceId",invoice.getId());firstEvidence.put("subscriptionId",subscription.getId());firstEvidence.put("chargeId",pi.getLatestCharge());firstEvidence.put("chargeCreated",failedAt);
        firstEvidence.put("businessObservedAt",controlledFailure!=null?first:oldBoundary+3600);firstEvidence.put("businessTimeResult",controlledFailure==null?"UNKNOWN_OBSERVATION_BOUND":"PROVEN_CONTROLLED_OPERATION");
        if(controlledFailure!=null)firstEvidence.put("businessFirstFailedAt",first);record("first-failure",firstEvidence);
        if(intro && !fixture.keepSchedule() && !fixture.priceUpdate()){
            var schedule=SubscriptionSchedule.retrieve(subscription.getSchedule(),options);owned(schedule.getLivemode(),schedule.getMetadata());
            schedule.release(Map.of("preserve_cancel_date",true),write("release-intro-for-no-schedule-fixture"));subscription=Subscription.retrieve(subscription.getId(),options);
        }
        var actualSchedule=subscription.getSchedule()==null?null:SubscriptionSchedule.retrieve(subscription.getSchedule(),options);
        var classified=RecoveryContractClassifier.classify(subscription,actualSchedule,true,price.getId(),1L);
        assertEquals(fixture.keepSchedule()?"EQUIVALENT_SCHEDULE":"NO_SCHEDULE",classified.scheduleClass(),"Actual native contract must match this recipe before recovery writes");
        if("true".equals(System.getenv("STRIPE_RECOVERY_APPLICATION_IT")))assertEquals(periodEnd?"PERIOD_END":"RENEW",classified.cancelMode(),"Cancellation preference must be native before application admission");
        record("fixture-contract-confirmed",Map.of("scheduleClass",classified.scheduleClass(),"contractHash",classified.contractHash(),"currency",invoice.getCurrency(),"invoiceAmount",invoice.getAmountDue()));
        assertEquals(fixture.currency(),invoice.getCurrency());assertEquals(fixture.unitAmountMinor(),invoice.getAmountDue());
        if("true".equals(System.getenv("STRIPE_RECOVERY_APPLICATION_IT"))){
            assertNotNull(controlledFailure);
            application=new RecoverySandboxApplicationHarness(run,options.getApiKey(),fixture,controlledFailure.event(),ownedClockEvidence,
                    customer.getId(),subscription.getId(),invoice.getId(),initialInvoice.getLines().getData().get(0).getPrice().getId(),price.getId(),fundsSetup,this::record);
            application.admitAndProtect();
            if(replacementCheckout){
                stage=replacementDouble?"APPLICATION_REPLACEMENT_DOUBLE_PAYMENT":"APPLICATION_REPLACEMENT_CHECKOUT";
                if(replacementDouble)application.acceptReplacementDouble(Path.of("/private/tmp/recovery-replacement-challenge.json"));
                else application.acceptReplacement(Path.of("/private/tmp/recovery-replacement-challenge.json"));
                return;
            }
            if(portalHosted){
                stage="APPLICATION_PORTAL_HOSTED";
                var baseline=new LinkedHashMap<String,Boolean>();var baselineRows=new ArrayList<Map<String,Object>>();
                for(var existing:com.stripe.model.billingportal.Configuration.list(Map.of("limit",100L),options).autoPagingIterable()){
                    assertEquals(Boolean.FALSE,existing.getLivemode());baseline.put(existing.getId(),Boolean.TRUE.equals(existing.getIsDefault()));
                    baselineRows.add(Map.of("id",existing.getId(),"metadata",existing.getMetadata(),"isDefault",Boolean.TRUE.equals(existing.getIsDefault()),"active",Boolean.TRUE.equals(existing.getActive())));
                }
                record("portal-configuration-baseline",Map.of("configurations",baselineRows,"existingConfigurationsModified",false));
                var portalConfig=creations.create("create-portal-method-only-configuration",RecoverySandboxPortalHosted.configurationParameters(run),
                        (parameters,key)->com.stripe.model.billingportal.Configuration.create(parameters,createOptions(key)),created->{
                            owned(created.getLivemode(),created.getMetadata());portalConfigurations.add(created.getId());
                            assertEquals(Boolean.TRUE,created.getActive());
                        });
                record("portal-configuration-created",Map.of("configurationId",portalConfig.getId(),"active",portalConfig.getActive(),"isDefault",portalConfig.getIsDefault(),"firstConfigurationBecameDefault",baseline.isEmpty()&&Boolean.TRUE.equals(portalConfig.getIsDefault()),"existingConfigurationsModified",false));
                application.acceptPortalHosted(portalConfig,Path.of("/private/tmp/recovery-portal-hosted-challenge.json"));
                for(var entry:baseline.entrySet())assertEquals(entry.getValue(),Boolean.TRUE.equals(com.stripe.model.billingportal.Configuration.retrieve(entry.getKey(),options).getIsDefault()),"Existing default selection must remain unchanged");
                record("portal-configuration-default-preserved",Map.of("createdConfigurationId",portalConfig.getId(),"createdIsDefault",Boolean.TRUE.equals(portalConfig.getIsDefault()),"firstConfigurationBecameDefault",baseline.isEmpty()&&Boolean.TRUE.equals(portalConfig.getIsDefault()),"existingConfigurationsModified",false));
                return;
            }
        }
        stage = "PROTECTION";
        if(application==null){
            invoice = invoice.update(Map.of("auto_advance", false), write("disable-advance"));
            subscription = subscription.update(Map.of("pause_collection", Map.of("behavior", "keep_as_draft")), write("pause"));
        }else{invoice=Invoice.retrieve(invoice.getId(),options);subscription=Subscription.retrieve(subscription.getId(),options);}
        if (periodEnd) subscription = subscription.update(Map.of("cancel_at_period_end", true), write("period-end"));
        snapshot("protected", subscription);
        assertFalse(Invoice.retrieve(invoice.getId(), options).getAutoAdvance());
        assertEquals("keep_as_draft", subscription.getPauseCollection().getBehavior());
        long protectedOriginalPeriodEnd = subscription.getCurrentPeriodEnd();
        if(mailAcceptance){
            stage="APPLICATION_MAIL_ACCEPTANCE";assertNotNull(application);application.acceptControlledMail();return;
        }
        if(activeCancel){
            stage="APPLICATION_ACTIVE_CANCEL";
            assertNotNull(application);advance(clock.getId(),first+Duration.ofDays(10).toSeconds());
            // These are owned fixture user actions. The application only consumes their native events.
            var reservedBody=Map.<String,Object>of("cancel_at_period_end",true);
            var reservedStart=Instant.now().minusSeconds(1);
            record("application-fixture-cancel-intent",Map.of("subscriptionId",subscription.getId(),"parameters",reservedBody,"idempotencyKey",run+":active-cancel-reserve","purpose","TEST_FIXTURE_USER_CANCEL_RESERVATION"));
            subscription=subscription.update(reservedBody,write("active-cancel-reserve"));
            var reservation=nativeSubscriptionEvent("customer.subscription.updated",subscription,subscription.getLastResponse().requestId(),reservedStart,true);
            application.acceptCancellationReservation(reservation);
            var cancelBody=SubscriptionRecoveryStripeGateway.cancelParams();var cancelStart=Instant.now().minusSeconds(1);
            record("application-fixture-cancel-intent",Map.of("subscriptionId",subscription.getId(),"parameters",cancelBody,"idempotencyKey",run+":active-cancel-now","purpose","TEST_FIXTURE_USER_NATIVE_CANCEL_NOT_APPLICATION_ACTION"));
            subscription=subscription.cancel(cancelBody,write("active-cancel-now"));
            var deleted=nativeSubscriptionEvent("customer.subscription.deleted",subscription,subscription.getLastResponse().requestId(),cancelStart,false);
            application.closeFromNativeCancellation(deleted);
            return;
        }
        if(d30Closure&&application!=null&&!fundsSetup){
            stage="APPLICATION_D30_CLOSURE";
            advance(clock.getId(),first+Duration.ofDays(30).toSeconds()-1);application.assertBeforeOriginalDeadline();
            advance(clock.getId(),first+Duration.ofDays(30).toSeconds());application.closeAtOriginalDeadline();
            return; // Actual service closure; API-only cancellation below is never entered.
        }
        if(d30Closure&&application==null){
            stage="D30_NATIVE_CLOSURE";assertNotNull(controlledFailure);advance(clock.getId(),first+Duration.ofDays(30).toSeconds());
            var currentInvoice=Invoice.retrieve(invoice.getId(),options);var currentSub=Subscription.retrieve(subscription.getId(),options);var currentPi=PaymentIntent.retrieve(currentInvoice.getPaymentIntent(),options);
            var replay=new RecoveryOwnedClockGateway(run,ownedClockEvidence).readFirstFailure(new SubscriptionRecoveryStripeGateway.Snapshot(currentInvoice,currentSub,null,currentInvoice.getLines().getData(),currentPi,Charge.retrieve(currentPi.getLatestCharge(),options),Instant.ofEpochSecond(first+30*86400L)),controlledFailure.event());assertEquals(Instant.ofEpochSecond(first),replay.occurredAt());
            var beforeClose=new HashSet<String>();for(var old:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100L),options).autoPagingIterable())beforeClose.add(old.getId());
            currentSub.cancel(SubscriptionRecoveryStripeGateway.cancelParams(),write("d30-cancel-subscription"));
            var afterClose=new HashSet<String>();
            for(var old:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100L),options).autoPagingIterable()){
                assertEquals(Boolean.FALSE,old.getLivemode());assertEquals(customer.getId(),old.getCustomer());assertEquals(subscription.getId(),old.getSubscription());afterClose.add(old.getId());
                if(Set.of("paid","void").contains(old.getStatus()))continue;
                assertEquals(0L,old.getAmountPaid());old=old.update(Map.of("auto_advance",false),write("d30-protect-"+old.getId()));
                if("draft".equals(old.getStatus()))old=old.finalizeInvoice(SubscriptionRecoveryStripeGateway.finalizeParams(),write("d30-finalize-"+old.getId()));
                old.voidInvoice(Map.of(),write("d30-void-"+old.getId()));
            }
            assertEquals(beforeClose,afterClose,"Cancellation must not create a final invoice");assertEquals("canceled",Subscription.retrieve(subscription.getId(),options).getStatus());
            for(var old:Invoice.list(Map.of("subscription",subscription.getId(),"limit",100L),options).autoPagingIterable())assertTrue(Set.of("paid","void").contains(old.getStatus()));
            assertNoUnexpectedCash(customer.getId(),subscription.getId(),Set.of(initialInvoice.getId()),"d30-close");
            record("d30-native-closure",Map.of("result","PASS","invoiceId",invoice.getId(),"subscriptionId",subscription.getId(),"firstFailureBusinessAt",first,"closedAtBusinessTime",first+30*86400L,"invoiceCount",afterClose.size(),"finalInvoiceCreated",false,"applicationD30Result","NOT_RUN","mailResult","NOT_RUN"));return;
        }
        stage = "FOUR_PAY_ATTEMPTS";
        Set<String> attempts = new HashSet<>();
        for (int slot = 1; slot <= 4; slot++) {
            advance(clock.getId(), first + Duration.ofDays(slot * 7L).toSeconds());
            if (slot == 4 && !d30Closure) {
                if(application!=null&&fundsSetup){
                    var methodBody=Map.<String,Object>of("invoice_settings",Map.of("default_payment_method",good.getId()));
                    record("application-fixture-method-intent",Map.of("customerId",customer.getId(),"parameters",methodBody,"idempotencyKey",run+":repay-customer-default-good","purpose","D28_USER_CHANGED_CARD_TEST_PREPARATION"));
                    customer.update(methodBody,write("repay-customer-default-good"));
                }
                subscription.update(Map.of("default_payment_method", good.getId()), write("repay-default-good"));
                invoice.update(Map.of("default_payment_method", good.getId()), write("repay-invoice-good"));
            }
            if(application!=null&&fundsSetup)application.runAutomaticRetrySlot(slot,slot==4&&!d30Closure);
            else{
                try {
                    invoice.pay(Map.of("off_session", true), write("retry-" + slot));
                    assertEquals(4, slot, "Only D28 should succeed");
                } catch (CardException e) {
                    assertTrue(slot < 4);
                    assertEquals(failureFixture, e.getDeclineCode());
                    record("decline", Map.of("slot", slot, "requestId", Objects.toString(e.getRequestId(), "")));
                }
            }
            invoice = Invoice.retrieve(invoice.getId(), options);
            snapshot("subscription-after-slot-" + slot, Subscription.retrieve(subscription.getId(), options));
            pi = PaymentIntent.retrieve(invoice.getPaymentIntent(), options);
            assertNotNull(pi.getLatestCharge());
            assertTrue(attempts.add(pi.getLatestCharge()), "Each business slot must have a distinct Charge");
            if(controlledFailure!=null){
                var nativeSub=Subscription.retrieve(subscription.getId(),options);var business=Instant.ofEpochSecond(TestClock.retrieve(clock.getId(),options).getFrozenTime());
                var replay=new RecoveryOwnedClockGateway(run,ownedClockEvidence).readFirstFailure(new SubscriptionRecoveryStripeGateway.Snapshot(invoice,nativeSub,null,invoice.getLines().getData(),pi,Charge.retrieve(pi.getLatestCharge(),options),business),controlledFailure.event());assertEquals(Instant.ofEpochSecond(first),replay.occurredAt());assertEquals(business,replay.businessObservedAt());
                record("controlled-proof-replayed",Map.of("slot",slot,"firstFailureBusinessAt",first,"businessObservedAt",business.getEpochSecond()));
            }
            record("retry", Map.of("slot", slot, "invoiceId", invoice.getId(), "status", invoice.getStatus(),
                    "chargeId", pi.getLatestCharge(), "attemptCount", invoice.getAttemptCount(), "autoAdvance", invoice.getAutoAdvance()));
        }
        if(d30Closure&&application!=null){
            assertTrue(fundsSetup);assertEquals("open",invoice.getStatus());assertEquals(0L,invoice.getAmountPaid());
            stage="APPLICATION_FUNDS_D30_CLOSURE";
            advance(clock.getId(),first+Duration.ofDays(30).toSeconds()-1);application.assertBeforeOriginalDeadline();
            advance(clock.getId(),first+Duration.ofDays(30).toSeconds());application.closeAtOriginalDeadline();
            return;
        }
        assertEquals("paid", invoice.getStatus());
        assertEquals(0L, invoice.getAmountRemaining());
        assertNotNull(invoice.getStatusTransitions().getPaidAt());
        Instant paidAt = Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt());
        record("payment-confirmed", Map.of("invoiceId", invoice.getId(), "paidAt", paidAt.getEpochSecond(), "source", "invoice.status_transitions.paid_at", "amountPaid", invoice.getAmountPaid()));
        if(application!=null){
            stage="APPLICATION_NATIVE_PAID_GRANT_AND_SYNC";
            application.grantFromNativePaidEvent();application.shiftUsingPersistedAction();
            if(annualRefresh){stage="APPLICATION_ANNUAL_MONTHLY_REFRESH";application.followAnnualMonthlyRefresh(clock.getId(),this::advance);}
            return; // Application/inbox/SQL scope; old-day and next-cycle audits remain separate API runs.
        }
        long paidEnd = (interval.equals("year") ? paidAt.atZone(ZoneOffset.UTC).plusYears(1)
                : paidAt.atZone(ZoneOffset.UTC).plusMonths(1)).toEpochSecond();
        subscription = Subscription.retrieve(subscription.getId(), options);
        long originalEnd = protectedOriginalPeriodEnd;
        if (subscription.getSchedule() != null) {
            SubscriptionSchedule schedule = SubscriptionSchedule.retrieve(subscription.getSchedule(), options);
            record("schedule-before-release", Map.of("id", schedule.getId(), "status", schedule.getStatus()));
            schedule.release(Map.of("preserve_cancel_date", true), write("release-schedule"));
        }
        // Protect every existing invoice before an atomic release experiment. No global reactivation.
        List<String> protectedDrafts = new ArrayList<>();
        for (Invoice old : Invoice.list(Map.of("subscription", subscription.getId(), "limit", 100), options).autoPagingIterable()) {
            if (Set.of("draft", "open", "uncollectible").contains(old.getStatus())) {
                old.update(Map.of("auto_advance", false), write("pre-shift-protect-" + old.getId()));
                assertFalse(Invoice.retrieve(old.getId(), options).getAutoAdvance());
                if ("draft".equals(old.getStatus())) protectedDrafts.add(old.getId());
            }
        }
        record("protected-drafts-before-shift", Map.of("ids", protectedDrafts));
        stage = atomicCandidate ? "ATOMIC_SHIFT_RENEWAL_AND_RELEASE" : "SHIFT_RENEWAL_UNDER_PROTECTION";
        snapshot("before-shift", Subscription.retrieve(subscription.getId(), options));
        record("shift-target", Map.of("paidPeriodEnd", paidEnd, "prorationBehavior", "none", "pauseCollection", "keep_as_draft"));
        var shift = SubscriptionUpdateParams.builder().setTrialEnd(paidEnd)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                .putMetadata("payment_recovery_invoice_id", invoice.getId());
        if (atomicCandidate) shift.setPauseCollection(com.stripe.param.common.EmptyParam.EMPTY);
        subscription = subscription.update(shift.build(), write("shift-renewal"));
        for (String draftId : protectedDrafts) {
            Invoice draft = Invoice.retrieve(draftId, options);
            record("protected-draft-after-shift", Map.of("id", draftId, "status", draft.getStatus(), "autoAdvance", draft.getAutoAdvance(), "amountPaid", draft.getAmountPaid()));
            assertEquals("draft", draft.getStatus()); assertFalse(draft.getAutoAdvance()); assertEquals(0L, draft.getAmountPaid());
        }
        snapshot("after-shift", subscription);
        assertEquals(paidEnd, subscription.getTrialEnd());
        assertEquals(paidEnd, subscription.getCurrentPeriodEnd());
        if (periodEnd) {
            assertTrue(subscription.getCancelAtPeriodEnd());
            assertEquals(paidEnd, subscription.getCancelAt(), "Candidate fails if cancellation remains on original date");
        }
        for (Invoice other : Invoice.list(Map.of("subscription", subscription.getId(), "limit", 100), options).autoPagingIterable()) {
            record("invoice-after-shift", Map.of("id", other.getId(), "status", other.getStatus(), "amountDue", other.getAmountDue(),
                    "amountPaid", other.getAmountPaid(), "autoAdvance", other.getAutoAdvance()));
            if (!other.getId().equals(invoice.getId()) && !"paid".equals(other.getStatus()) && !"void".equals(other.getStatus())) {
                assertEquals(0L, other.getAmountPaid());
                other = other.update(Map.of("auto_advance", false), write("protect-draft-" + other.getId()));
                if ("draft".equals(other.getStatus())) other = other.finalizeInvoice(Map.of("auto_advance", false), write("finalize-" + other.getId()));
                other.voidInvoice(Map.of(), write("void-" + other.getId()));
            }
        }
        if (!atomicCandidate) subscription = subscription.update(Map.of("pause_collection", ""), write("release-pause"));
        else assertNull(Subscription.retrieve(subscription.getId(), options).getPauseCollection());
        Set<String> expectedCashInvoices=new HashSet<>(List.of(initialInvoice.getId(),invoice.getId()));
        assertNoUnexpectedCash(customer.getId(),subscription.getId(),expectedCashInvoices,"post-shift");
        if (originalEnd > first + Duration.ofDays(28).toSeconds() && originalEnd < paidEnd) {
            advance(clock.getId(), originalEnd + 3600);
            assertEquals("trialing", Subscription.retrieve(subscription.getId(), options).getStatus());
            assertNoUnexpectedCash(customer.getId(),subscription.getId(),expectedCashInvoices,"old-renewal-boundary");
        }
        advance(clock.getId(), paidEnd);
        advance(clock.getId(), paidEnd + 3600);
        subscription = Subscription.retrieve(subscription.getId(), options);
        snapshot("paid-period-end", subscription);
        if (periodEnd) {
            assertEquals("canceled", subscription.getStatus());

        } else {
            Invoice next = Invoice.retrieve(subscription.getLatestInvoice(), options);
            record("next-invoice", Map.of("id", next.getId(), "status", next.getStatus(), "amountDue", next.getAmountDue(),
                    "amountPaid", next.getAmountPaid(), "periodStart", next.getLines().getData().get(0).getPeriod().getStart()));
            assertEquals("paid", next.getStatus());
            assertEquals(fixture.unitAmountMinor(), next.getAmountPaid());assertEquals(fixture.currency(),next.getCurrency());
            assertEquals(paidEnd, next.getLines().getData().get(0).getPeriod().getStart());
            assertTrue(expectedCashInvoices.add(next.getId()),"Next cycle must be a new invoice");
        }
        assertNoUnexpectedCash(customer.getId(),subscription.getId(),expectedCashInvoices,"paid-period-end");
    }

    private Event nativeSubscriptionEvent(String type,Subscription expected,String requestId,Instant operationStart,boolean reservation)throws Exception{
        owned(expected.getLivemode(),expected.getMetadata());assertNotNull(requestId);assertFalse(requestId.isBlank());
        for(int attempt=0;attempt<20;attempt++){
            for(var event:Event.list(Map.of("types",List.of(type),"created",Map.of("gte",operationStart.getEpochSecond()),"limit",100L),options).autoPagingIterable()){
                var raw=com.google.gson.JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
                if(!expected.getId().equals(nativeId(raw,"id"))||!expected.getCustomer().equals(nativeId(raw,"customer")))continue;
                var envelope=com.google.gson.JsonParser.parseString(event.toJson()).getAsJsonObject();
                if(!envelope.has("request")||!envelope.get("request").isJsonObject()||!requestId.equals(nativeId(envelope.getAsJsonObject("request"),"id")))continue;
                assertEquals(Boolean.FALSE,event.getLivemode());assertNotNull(event.getApiVersion());assertTrue(event.getCreated()>=operationStart.getEpochSecond());
                assertEquals(run,nativeId(raw.getAsJsonObject("metadata"),"recovery_test_run"));
                if(reservation){assertTrue(raw.get("cancel_at_period_end").getAsBoolean());assertNotEquals("canceled",nativeId(raw,"status"));}
                else assertEquals("canceled",nativeId(raw,"status"));
                record("application-native-cancel-event",Map.of("eventId",event.getId(),"type",type,"subscriptionId",expected.getId(),"customerId",expected.getCustomer(),"providerRequestId",requestId,"nativeEventVersion",event.getApiVersion(),"nativeCreated",event.getCreated(),"operationRealStart",operationStart.getEpochSecond()));
                return event;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Native subscription event with exact owned mutation request ID is required: "+type);
    }

    private void assertNoUnexpectedCash(String customerId,String subscriptionId,Set<String> expectedCashInvoices,String boundary)throws Exception{
        var invoices=new ArrayList<Map<String,Object>>();var charges=new ArrayList<Map<String,Object>>();
        for(var invoice:Invoice.list(Map.of("subscription",subscriptionId,"limit",100),options).autoPagingIterable()){
            assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals(customerId,invoice.getCustomer());
            invoices.add(Map.of("id",invoice.getId(),"status",invoice.getStatus(),"amountDue",invoice.getAmountDue(),"amountPaid",invoice.getAmountPaid(),"autoAdvance",invoice.getAutoAdvance()));
            if(invoice.getAmountPaid()>0 || (invoice.getAmountDue()>0 && !"void".equals(invoice.getStatus())))
                assertTrue(expectedCashInvoices.contains(invoice.getId()),"Unexpected positive invoice at "+boundary+": "+invoice.getId());
        }
        for(var charge:Charge.list(Map.of("customer",customerId,"limit",100),options).autoPagingIterable()){
            assertEquals(Boolean.FALSE,charge.getLivemode());
            var row=new LinkedHashMap<String,Object>();row.put("id",charge.getId());row.put("invoiceId",charge.getInvoice());row.put("paymentIntentId",charge.getPaymentIntent());row.put("status",charge.getStatus());row.put("paid",charge.getPaid());row.put("amountCaptured",charge.getAmountCaptured());charges.add(row);
            if(Boolean.TRUE.equals(charge.getPaid()) || (charge.getAmountCaptured()!=null && charge.getAmountCaptured()>0))
                assertTrue(expectedCashInvoices.contains(charge.getInvoice()),"Unexpected actual cash at "+boundary+": "+charge.getId());
        }
        record("cash-boundary-audit",Map.of("boundary",boundary,"expectedCashInvoiceIds",expectedCashInvoices,"invoices",invoices,"charges",charges,"result","PASS"));
    }

    private RecoveryOwnedClockEvidence.PreAttempt explicitPayPreflight(Invoice candidate)throws Exception{
        var invoice=Invoice.retrieve(candidate.getId(),options);assertEquals(Boolean.FALSE,invoice.getLivemode());assertNotNull(invoice.getPaymentIntent());
        assertEquals("open",invoice.getStatus());assertEquals(Boolean.FALSE,invoice.getAutoAdvance());assertEquals(0L,invoice.getAmountPaid());assertTrue(invoice.getAmountRemaining()>0);
        assertEquals(Boolean.FALSE,invoice.getAttempted());assertEquals(0L,invoice.getAttemptCount());assertNotNull(invoice.getStatusTransitions().getFinalizedAt());
        assertEquals(Boolean.FALSE,invoice.getLines().getHasMore());assertEquals(1,invoice.getLines().getData().size());
        var pi=PaymentIntent.retrieve(invoice.getPaymentIntent(),options);assertEquals("requires_confirmation",pi.getStatus());assertEquals(0L,pi.getAmountReceived());assertNull(pi.getLatestCharge());assertNull(pi.getLastPaymentError());assertNull(pi.getNextAction());
        var charges=new HashSet<String>();for(var charge:Charge.list(Map.of("payment_intent",pi.getId(),"limit",100L),options).autoPagingIterable())charges.add(charge.getId());assertTrue(charges.isEmpty());
        var failed=new HashSet<String>();var required=new HashSet<String>();
        for(var event:Event.list(Map.of("types",List.of("invoice.payment_failed","invoice.payment_action_required"),"limit",100L),options).autoPagingIterable()){
            var raw=com.google.gson.JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
            if(invoice.getId().equals(nativeId(raw,"id"))){if("invoice.payment_failed".equals(event.getType()))failed.add(event.getId());else required.add(event.getId());}
        }
        assertTrue(failed.isEmpty());assertTrue(required.isEmpty());
        var fact=new RecoveryOwnedClockEvidence.InvoiceFact(invoice.getId(),invoice.getCustomer(),invoice.getSubscription(),invoice.getPaymentIntent(),invoice.getLines().getData().get(0).getPrice().getId(),invoice.getLivemode(),invoice.getCreated());
        var intent=new RecoveryOwnedClockEvidence.PrePaymentIntent(pi.getId(),pi.getCustomer(),pi.getLivemode(),pi.getStatus(),pi.getAmountReceived(),pi.getLatestCharge(),null,null);
        return new RecoveryOwnedClockEvidence.PreAttempt(fact,invoice.getStatus(),invoice.getAutoAdvance(),invoice.getAmountPaid(),invoice.getAmountRemaining(),invoice.getAttempted(),invoice.getAttemptCount(),invoice.getStatusTransitions().getFinalizedAt(),intent,charges,failed,required,true,true,true,true,true);
    }

    private static String eventRequestId(Event event){
        var raw=com.google.gson.JsonParser.parseString(event.toJson()).getAsJsonObject();
        return raw.has("request")&&raw.get("request").isJsonObject()?nativeId(raw.getAsJsonObject("request"),"id"):null;
    }

    private ControlledFailure captureControlledFailure(String clockId,TestClock beforeClock,Subscription beforeSub,Subscription afterSub,Invoice invoice,PaymentIntent pi,String requestId,Instant realStart,Set<String> beforeInvoices,Set<String> beforeFormal,Set<String> usedPrices,String mode,RecoveryOwnedClockEvidence.PreAttempt preAttempt)throws Exception{
        var charges=new ArrayList<RecoveryOwnedClockEvidence.ChargeFact>();
        for(var ch:Charge.list(Map.of("payment_intent",pi.getId(),"limit",100),options).autoPagingIterable())charges.add(new RecoveryOwnedClockEvidence.ChargeFact(ch.getId(),ch.getCustomer(),ch.getInvoice(),ch.getPaymentIntent(),ch.getStatus(),ch.getLivemode(),ch.getCreated()));
        var events=new ArrayList<Event>();
        for(int attempt=0;attempt<20;attempt++){
            events.clear();
            for(var e:Event.list(Map.of("types",List.of("invoice.payment_failed","invoice.payment_action_required"),"created",Map.of("gte",realStart.getEpochSecond()),"limit",100),options).autoPagingIterable()){
                var raw=com.google.gson.JsonParser.parseString(e.getDataObjectDeserializer().getRawJson()).getAsJsonObject();if(invoice.getId().equals(nativeId(raw,"id")))events.add(e);
            }
            if(!events.isEmpty())break;Thread.sleep(250);
        }
        var failures=new ArrayList<RecoveryOwnedClockEvidence.FailureEventFact>();
        for(var e:events){
            var raw=com.google.gson.JsonParser.parseString(e.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
            String eventSubscription=nativeId(raw,"subscription"),schema="ROOT_INVOICE";
            if(eventSubscription==null && raw.has("parent") && raw.get("parent").isJsonObject()){
                var parent=raw.getAsJsonObject("parent");
                if("subscription_details".equals(nativeId(parent,"type")) && parent.has("subscription_details") && parent.get("subscription_details").isJsonObject()){
                    eventSubscription=nativeId(parent.getAsJsonObject("subscription_details"),"subscription");schema="PARENT_SUBSCRIPTION_DETAILS";
                }
            }
            failures.add(new RecoveryOwnedClockEvidence.FailureEventFact(e.getId(),e.getType(),nativeId(raw,"id"),nativeId(raw,"customer"),eventSubscription,nativeId(raw,"payment_intent"),e.getLivemode(),e.getCreated(),e.getApiVersion(),schema,eventRequestId(e)));
        }
        var afterClock=TestClock.retrieve(clockId,options);assertEquals(run,afterClock.getName());assertEquals(Boolean.FALSE,afterClock.getLivemode());owned(beforeSub.getLivemode(),beforeSub.getMetadata());owned(afterSub.getLivemode(),afterSub.getMetadata());
        var frame=new RecoveryOwnedClockEvidence.Frame(new RecoveryOwnedClockEvidence.Scope(run,clockId,invoice.getCustomer(),afterSub.getId()),
                new RecoveryOwnedClockEvidence.ClockState(clockId,beforeClock.getStatus(),Instant.ofEpochSecond(beforeClock.getFrozenTime())),new RecoveryOwnedClockEvidence.ClockState(clockId,afterClock.getStatus(),Instant.ofEpochSecond(afterClock.getFrozenTime())),
                subFact(beforeSub),subFact(afterSub),beforeInvoices,beforeFormal,usedPrices,requestId,
                new RecoveryOwnedClockEvidence.InvoiceFact(invoice.getId(),invoice.getCustomer(),invoice.getSubscription(),invoice.getPaymentIntent(),invoice.getLines().getData().get(0).getPrice().getId(),invoice.getLivemode(),invoice.getCreated()),
                new RecoveryOwnedClockEvidence.PaymentIntentFact(pi.getId(),pi.getCustomer(),pi.getLatestCharge(),pi.getLivemode()),charges,failures,mode,preAttempt);
        var proof=ownedClockEvidence.register(frame);var saved=new LinkedHashMap<String,Object>();saved.put("businessFirstFailedAt",proof.occurredAt().getEpochSecond());saved.put("source","owned_test_clock_controlled_operation");saved.put("operationMode",mode);if(preAttempt!=null)saved.put("preAttempt",preAttempt);saved.put("scope",frame.scope());saved.put("beforeClock",Map.of("status",beforeClock.getStatus(),"frozenTime",beforeClock.getFrozenTime(),"name",beforeClock.getName(),"livemode",beforeClock.getLivemode()));saved.put("afterClock",Map.of("status",afterClock.getStatus(),"frozenTime",afterClock.getFrozenTime(),"name",afterClock.getName(),"livemode",afterClock.getLivemode()));saved.put("beforeInvoiceIds",beforeInvoices);saved.put("beforeFormalInvoiceIds",beforeFormal);saved.put("usedPriceIds",usedPrices);saved.put("operationRequestId",requestId);saved.put("beforeSubscription",frame.beforeSubscription());saved.put("afterSubscription",frame.afterSubscription());saved.put("invoice",frame.invoice());saved.put("intent",frame.paymentIntent());saved.put("charges",charges);saved.put("failureEvents",failures);record("controlled-first-failure-evidence",saved);
        return new ControlledFailure(proof,events.get(0));
    }
    static RecoveryOwnedClockEvidence.SubscriptionState subFact(Subscription s){
        assertNotNull(s);assertEquals(Boolean.FALSE,s.getLivemode());assertNotNull(s.getItems());assertEquals(Boolean.FALSE,s.getItems().getHasMore());assertNotNull(s.getItems().getData());assertEquals(1,s.getItems().getData().size());
        var item=s.getItems().getData().get(0);assertNotNull(item.getId());assertNotNull(item.getPrice());assertNotNull(item.getPrice().getId());assertEquals(1L,item.getQuantity());
        return new RecoveryOwnedClockEvidence.SubscriptionState(s.getId(),s.getCustomer(),s.getTestClock(),item.getId(),item.getPrice().getId(),s.getLivemode(),item.getQuantity());
    }
    private static String nativeId(com.google.gson.JsonObject raw,String key){var value=raw.get(key);if(value==null||value.isJsonNull())return null;return value.isJsonObject()?value.getAsJsonObject().get("id").getAsString():value.getAsString();}

    private Map<String, String> metadata(String caseKey) { return Map.of("recovery_test_run", run, "case_key", caseKey); }
    private void owned(Boolean live, Map<String, String> metadata) {
        assertEquals(Boolean.FALSE, live);
        assertEquals(run, metadata.get("recovery_test_run"));
    }
    private RequestOptions createOptions(String idempotencyKey) {
        return options.toBuilder().setIdempotencyKey(idempotencyKey).build();
    }
    private void registerClockSubscription(Subscription created, String customerId, String clockId) throws Exception {
        owned(created.getLivemode(), created.getMetadata());
        assertEquals(customerId, created.getCustomer()); assertEquals(clockId, created.getTestClock());
        assertTrue(clocks.contains(clockId), "Subscription cleanup must be covered by a known owned clock");
        record("created-subscription", Map.of("id", created.getId(), "customerId", customerId, "cleanupClockId", clockId));
    }
    private void registerClockSchedule(SubscriptionSchedule created, String customerId, String clockId) throws Exception {
        owned(created.getLivemode(), created.getMetadata());
        assertEquals(customerId, created.getCustomer()); assertEquals(clockId, created.getTestClock());
        assertTrue(clocks.contains(clockId), "Schedule cleanup must be covered by a known owned clock");
        record("created-schedule-cleanup-owner", Map.of("id", created.getId(), "customerId", customerId, "cleanupClockId", clockId));
    }
    private RequestOptions write(String action) throws Exception {
        // Persist intent before every network mutation, including test cleanup.
        String idempotency = run + ":" + action;
        record("intent", Map.of("action", action, "idempotencyKey", idempotency));
        return options.toBuilder().setIdempotencyKey(idempotency).build();
    }
    private void record(String phase, Map<String, ?> values) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.put("phase", phase); row.put("realTime", Instant.now().toString()); row.put("sequence", ++sequence);
        Files.writeString(journal, new com.google.gson.Gson().toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("RECOVERY_SANDBOX " + phase + " " + row);
    }
    private void snapshot(String phase, Subscription s) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", s.getId()); values.put("status", s.getStatus()); values.put("periodStart", s.getCurrentPeriodStart());
        values.put("periodEnd", s.getCurrentPeriodEnd()); values.put("trialEnd", s.getTrialEnd());
        values.put("cancelAtPeriodEnd", s.getCancelAtPeriodEnd()); values.put("cancelAt", s.getCancelAt());
        values.put("scheduleId", s.getSchedule()); values.put("latestInvoice", s.getLatestInvoice());
        record(phase, values);
    }
    private void advance(String id, long time) throws Exception {
        TestClock clock = TestClock.retrieve(id, options);
        assertEquals(Boolean.FALSE, clock.getLivemode());
        assertEquals(run, clock.getName());
        clock.advance(Map.of("frozen_time", time), write("advance-" + time));
        for (int i = 0; i < 120; i++) {
            clock = TestClock.retrieve(id, options);
            if ("ready".equals(clock.getStatus())) { record("advanced", Map.of("clockId", id, "frozenTime", time)); return; }
            Thread.sleep(1000);
        }
        fail("Clock did not become ready; follow journal for owned-object cleanup");
    }
    private void cleanup() throws Exception {
        List<Exception> failures = new ArrayList<>();
        if(applicationCleanupFailed)failures.add(new IllegalStateException("APPLICATION_CLEANUP_FAILED_FOLLOW_OWNED_OBJECT_JOURNAL"));
        for(String id:portalConfigurations)try{
            var configuration=com.stripe.model.billingportal.Configuration.retrieve(id,options);owned(configuration.getLivemode(),configuration.getMetadata());
            assertEquals("portal_method_only",configuration.getMetadata().get("purpose"));var body=Map.<String,Object>of("active",false);
            record("cleanup-portal-configuration-intent",Map.of("configurationId",id,"parameters",body,"idempotencyKey",run+":deactivate-portal-"+id));
            configuration=configuration.update(body,write("deactivate-portal-"+id));owned(configuration.getLivemode(),configuration.getMetadata());assertEquals(Boolean.FALSE,configuration.getActive());
            record("cleanup-portal-configuration",Map.of("configurationId",id,"active",false,"onlyOwnedNewConfiguration",true));
        }catch(Exception|AssertionError e){
            failures.add(new IllegalStateException("PORTAL_CONFIGURATION_CLEANUP_UNKNOWN:"+id));
            record("cleanup-portal-configuration",Map.of("configurationId",id,"result","UNKNOWN","errorType",e.getClass().getSimpleName()));
        }
        // Resolve explicitly registered SetupIntents before deleting their owned clock/customer.
        // A clock deletion alone does not prove a SetupIntent reached a safe known terminal state.
        for(var entry:fundsSetupCustomers.entrySet())try{
            var setup=SetupIntent.retrieve(entry.getKey(),options.toBuilder().setMaxNetworkRetries(0).build());
            owned(setup.getLivemode(),setup.getMetadata());assertEquals(entry.getValue(),setup.getCustomer());
            if(Set.of("requires_payment_method","requires_confirmation","requires_action").contains(setup.getStatus())){
                var body=Map.<String,Object>of("cancellation_reason","abandoned");String key=run+":cancel-funds-setup-"+setup.getId();
                record("cleanup-funds-setup-intent",Map.of("setupIntentId",setup.getId(),"customerId",entry.getValue(),"parameters",body,"idempotencyKey",key));
                setup=setup.cancel(body,options.toBuilder().setMaxNetworkRetries(0).setIdempotencyKey(key).build());
                owned(setup.getLivemode(),setup.getMetadata());assertEquals(entry.getValue(),setup.getCustomer());assertEquals("canceled",setup.getStatus());
            }
            if(!Set.of("succeeded","canceled").contains(setup.getStatus()))throw new IllegalStateException("FUNDS_SETUP_CLEANUP_NOT_TERMINAL");
            record("cleanup-funds-setup",Map.of("setupIntentId",setup.getId(),"state",setup.getStatus(),"result","TERMINAL_HISTORY_RETAINED"));
        }catch(Exception|AssertionError e){
            // Avoid exposing authentication secrets through an SDK exception or nested cause.
            failures.add(new IllegalStateException("FUNDS_SETUP_CLEANUP_UNKNOWN:"+entry.getKey()));
            record("cleanup-funds-setup",Map.of("setupIntentId",entry.getKey(),"result","UNKNOWN","errorType",e.getClass().getSimpleName()));
        }
        for (String id : clocks) try {
            TestClock clock = TestClock.retrieve(id, options);
            assertEquals(run, clock.getName()); assertEquals(Boolean.FALSE, clock.getLivemode());
            clock.delete(write("delete-clock-" + id)); record("cleanup-clock", Map.of("id", id, "result", "deleted"));
        } catch (Exception e) { failures.add(e); }
        for (String id : prices) try {
            Price price = Price.retrieve(id, options); owned(price.getLivemode(), price.getMetadata());
            price.update(Map.of("active", false), write("archive-price-" + id));
        } catch (Exception e) { failures.add(e); }
        for (String id : products) try {
            Product product = Product.retrieve(id, options); owned(product.getLivemode(), product.getMetadata());
            product.update(Map.of("active", false), write("archive-product-" + id));
        } catch (Exception e) { failures.add(e); }
        String cleanupResult = creations.cleanupResult(failures.size());
        record("cleanup", Map.of("result", cleanupResult, "failedSteps", failures.size(), "pendingCreates", creations.pending()));
        if (!failures.isEmpty()) throw failures.get(0);
        if (!creations.pending().isEmpty()) throw new IllegalStateException("Cleanup UNKNOWN: unresolved create intents; follow exact journal parameters and idempotency keys");
    }
    private String safeMessage(Throwable e) {
        // SDK messages may contain object IDs; never persist request bodies or URLs with payment secrets.
        String message = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
        return message.replaceAll("(?:sk|rk)_(?:test|live)_[A-Za-z0-9]+", "[REDACTED]")
                .replaceAll("https?://\\S+", "[URL REDACTED]");
    }
}
