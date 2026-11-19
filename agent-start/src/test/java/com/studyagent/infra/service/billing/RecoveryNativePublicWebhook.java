package com.studyagent.infra.service.billing;

import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.model.WebhookEndpoint;
import com.stripe.net.RequestOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Owned TEST endpoint and anonymous tunnel. No Stripe CLI or existing account config is used. */
final class RecoveryNativePublicWebhook implements AutoCloseable {
    record Delivery(String eventId,String type,String objectId,int status,String signature,byte[] body) {}
    static final class OwnedProxy implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService threads=Executors.newCachedThreadPool();
        private final Queue<Delivery> deliveries=new ConcurrentLinkedQueue<>();
        private final String customer;
        private final URI target;
        private final String probe=UUID.randomUUID().toString();
        OwnedProxy(String customer,URI target)throws Exception {
            this.customer=customer;this.target=target;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
            server.createContext("/",exchange->{
                int status=400;
                try {
                    if("GET".equals(exchange.getRequestMethod())&&"/ready".equals(exchange.getRequestURI().getPath())){exchange.getResponseHeaders().set("X-Recovery-Owned-Proxy",probe);status=204;return;}
                    if(!"/v1/webhook/stripe".equals(exchange.getRequestURI().getPath())){status=404;return;}
                    if(!"POST".equals(exchange.getRequestMethod())){status=405;return;}
                    byte[] body=exchange.getRequestBody().readNBytes(1024*1024+1);
                    if(body.length>1024*1024){status=413;return;}
                    var event=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    var object=event.getAsJsonObject("data").getAsJsonObject("object");
                    var owner=object.get("customer");
                    // Ownership is only an ingress filter. The production controller verifies the actual HMAC.
                    if(owner==null||!owner.isJsonPrimitive()||!customer.equals(owner.getAsString())){status=200;return;}
                    String signature=exchange.getRequestHeaders().getFirst("Stripe-Signature");
                    if(signature==null){status=401;return;}
                    var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(90))
                            .header("Content-Type","application/json").header("Stripe-Signature",signature)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    status=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                    deliveries.add(new Delivery(event.get("id").getAsString(),event.get("type").getAsString(),object.get("id").getAsString(),status,signature,body));
                }catch(RuntimeException malformed){status=400;}
                catch(Exception unavailable){status=503;}
                finally{try{exchange.sendResponseHeaders(status,-1);}catch(Exception ignored){}exchange.close();}
            });server.start();
        }
        int port(){return server.getAddress().getPort();}
        String probe(){return probe;}
        List<Delivery> deliveries(){return List.copyOf(deliveries);}
        @Override public void close(){server.stop(0);threads.shutdownNow();}
    }
    private final RecoverySandboxApplicationHarness fixture;
    final OwnedProxy proxy;
    private Process tunnel;
    private WebhookEndpoint endpoint;
    private String url;
    private boolean createPending;
    private final Path log;
    RecoveryNativePublicWebhook(RecoverySandboxApplicationHarness fixture,int port,String customer)throws Exception {
        this.fixture=fixture;
        assertTrue(Stripe.apiKey!=null&&(Stripe.apiKey.startsWith("sk_test_")||Stripe.apiKey.startsWith("rk_test_")));
        assertEquals("2023-10-16",Stripe.API_VERSION);
        proxy=new OwnedProxy(customer,URI.create("http://127.0.0.1:"+port+"/v1/webhook/stripe"));
        Path dir=Path.of(System.getProperty("recovery.evidence.dir"));Files.createDirectories(dir);
        log=dir.resolve(fixture.ownedRunId()+"-tunnel.private.log");
        try{
            Path config=dir.resolve(fixture.ownedRunId()+"-tunnel.private.yml");Files.writeString(config,"{}\n",StandardOpenOption.CREATE_NEW);
            var builder=new ProcessBuilder("/opt/homebrew/bin/cloudflared","tunnel","--config",config.toString(),"--no-autoupdate",
                    "--protocol","http2","--metrics","127.0.0.1:0","--url","http://127.0.0.1:"+proxy.port());
            builder.environment().keySet().removeIf(k->k.startsWith("STRIPE_")||k.startsWith("CLERK_")||k.startsWith("RESEND_")||k.startsWith("TUNNEL_")||k.startsWith("CLOUDFLARE_")||k.startsWith("AWS_")||k.toUpperCase(Locale.ROOT).contains("SECRET")||k.toUpperCase(Locale.ROOT).contains("TOKEN")||k.toUpperCase(Locale.ROOT).contains("PASSWORD")||k.endsWith("API_KEY"));
            tunnel=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
            var pattern=Pattern.compile("https://[a-z0-9]+(?:-[a-z0-9]+){2,}\\.trycloudflare\\.com");
            while(url==null&&System.nanoTime()<deadline&&tunnel.isAlive()){
                var match=pattern.matcher(Files.exists(log)?Files.readString(log):"");if(match.find())url=match.group();else Thread.sleep(200);
            }
            if(url==null)throw new IllegalStateException("PUBLIC_TUNNEL_NOT_READY_SEE_PRIVATE_LOG");
            // Wait for the actual public route before registering any financial provider endpoint.
            boolean ready=false;
            for(int attempt=0;attempt<25&&!ready;attempt++){
                try{var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url+"/ready")).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.discarding());ready=response.statusCode()==204&&proxy.probe().equals(response.headers().firstValue("X-Recovery-Owned-Proxy").orElse(""));}
                catch(Exception notReady){Thread.sleep(400);}
            }
            if(!ready&&"root@77.42.123.251".equals(System.getenv("STRIPE_RECOVERY_PUBLIC_READY_SSH_HOST")))ready=externalReadyProbe();
            if(!ready)throw new IllegalStateException("PUBLIC_TUNNEL_ROUTE_UNREACHABLE");
            var params=Map.<String,Object>of("url",url+"/v1/webhook/stripe","api_version","2023-10-16",
                    "enabled_events",List.of("invoice.payment_failed","invoice.paid"),"metadata",Map.of("recovery_test_run",fixture.ownedRunId()),
                    "description","Owned isolated recovery TEST acceptance");
            String key=fixture.ownedRunId()+":create-public-webhook";
            fixture.evidence("public-webhook-create-intent",Map.of("parameters",params,"idempotencyKey",key));createPending=true;
            try{endpoint=WebhookEndpoint.create(params,options(key));createPending=false;}
            catch(com.stripe.exception.InvalidRequestException rejected){createPending=false;fixture.evidence("public-webhook-create-rejected",Map.of("httpStatus",rejected.getStatusCode(),"result","FAIL"));throw rejected;}
            owned(endpoint);assertNotNull(endpoint.getSecret());
            fixture.evidence("public-webhook-created",Map.of("endpointId",endpoint.getId(),"apiVersion",endpoint.getApiVersion(),"url",endpoint.getUrl(),"livemode",endpoint.getLivemode(),"providerRequestId",endpoint.getLastResponse().requestId()));
        }catch(Exception|AssertionError failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Read-only public GET from the authorized TEST host; never changes DNS, Host or server configuration. */
    private boolean externalReadyProbe()throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            var builder=new ProcessBuilder("ssh","-o","BatchMode=yes","-o","StrictHostKeyChecking=yes","-o","ConnectTimeout=10",
                    "root@77.42.123.251","python3","-");
            builder.environment().keySet().removeIf(k->k.startsWith("STRIPE_")||k.startsWith("CLERK_")||k.startsWith("RESEND_")
                    ||k.toUpperCase(Locale.ROOT).contains("SECRET")||k.toUpperCase(Locale.ROOT).contains("TOKEN")||k.toUpperCase(Locale.ROOT).contains("PASSWORD")||k.endsWith("API_KEY"));
            var process=builder.start();
            String script="import urllib.request,urllib.error,json\ntry:\n r=urllib.request.urlopen('"+url+"/ready',timeout=8);print(json.dumps({'status':r.status,'nonce':r.headers.get('X-Recovery-Owned-Proxy')}))\nexcept urllib.error.HTTPError as e: print(json.dumps({'status':e.code}))\nexcept Exception as e: print(json.dumps({'status':0,'errorClass':type(e).__name__}))\n";
            try{
                process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
                if(!process.waitFor(25,TimeUnit.SECONDS)||process.exitValue()!=0)return false;
                var result=JsonParser.parseString(new String(process.getInputStream().readNBytes(4096),StandardCharsets.UTF_8)).getAsJsonObject();
                int status=result.get("status").getAsInt();
                boolean ready=status==204&&result.has("nonce")&&!result.get("nonce").isJsonNull()&&proxy.probe().equals(result.get("nonce").getAsString());
                fixture.evidence("public-route-ready-external",Map.of("scope","AUTHORIZED_TEST_HOST_READ_ONLY_PUBLIC_GET","ready",ready,"httpStatus",status,"attempt",attempt+1,"noDnsOverride",true,"noServerConfigurationChange",true));
                if(ready)return true;
                if(status==403)return false; // Do not route around an access denial.
            }finally{if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}}
            Thread.sleep(2000);
        }
        return false;
    }
    String signingSecret(){return endpoint.getSecret();}
    String endpointId(){return endpoint.getId();}
    Delivery awaitDelivery(String type,String invoice)throws Exception{
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        while(System.nanoTime()<deadline){
            for(var delivery:proxy.deliveries())if(type.equals(delivery.type())&&invoice.equals(delivery.objectId())&&delivery.status()==200)return delivery;
            Thread.sleep(100);
        }
        throw new IllegalStateException("PUBLIC_STRIPE_DELIVERY_NOT_OBSERVED:"+type);
    }
    void replay(Delivery delivery,int port)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/webhook/stripe")).timeout(Duration.ofSeconds(90))
                .header("Content-Type","application/json").header("Stripe-Signature",delivery.signature())
                .POST(HttpRequest.BodyPublishers.ofByteArray(delivery.body())).build();
        assertEquals(200,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    Delivery causeOwnedFailure(String invoice)throws Exception{
        var before=com.stripe.model.Invoice.retrieve(invoice);assertEquals(Boolean.FALSE,before.getLivemode());assertEquals("open",before.getStatus());
        String attachKey=fixture.ownedRunId()+":public-failure-method";
        fixture.evidence("public-failure-method-intent",Map.of("parameters",Map.of("customer",before.getCustomer()),"testMethod","pm_card_chargeCustomerFail","idempotencyKey",attachKey));
        var bad=com.stripe.model.PaymentMethod.retrieve("pm_card_chargeCustomerFail").attach(Map.of("customer",before.getCustomer()),options(attachKey));
        assertEquals(Boolean.FALSE,bad.getLivemode());assertEquals(before.getCustomer(),bad.getCustomer());
        String payKey=fixture.ownedRunId()+":public-native-failure";var body=Map.<String,Object>of("off_session",true,"payment_method",bad.getId());
        fixture.evidence("public-native-failure-intent",Map.of("invoiceId",invoice,"parameters",body,"idempotencyKey",payKey));
        String requestId;
        try{before.pay(body,options(payKey));throw new AssertionError("Owned generic-decline payment must really fail");}
        catch(com.stripe.exception.CardException declined){assertEquals("generic_decline",declined.getDeclineCode());requestId=declined.getRequestId();}
        var delivery=awaitDelivery("invoice.payment_failed",invoice);recordDelivery(delivery,requestId);return delivery;
    }
    void recordDelivery(Delivery delivery,String expectedRequest)throws Exception{
        var event=JsonParser.parseString(new String(delivery.body(),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("2023-10-16",event.get("api_version").getAsString());assertFalse(event.get("livemode").getAsBoolean());
        String request=event.getAsJsonObject("request").get("id").getAsString();if(expectedRequest!=null)assertEquals(expectedRequest,request);
        fixture.evidence("public-native-delivery",Map.of("endpointId",endpoint.getId(),"eventId",delivery.eventId(),"type",delivery.type(),"objectId",delivery.objectId(),
                "status",delivery.status(),"apiVersion",event.get("api_version").getAsString(),"providerRequestId",request,
                "payloadSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(delivery.body())),"origin","STRIPE_PUBLIC_DELIVERY"));
    }
    private RequestOptions options(String key){return RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build();}
    private void owned(WebhookEndpoint value){assertEquals(Boolean.FALSE,value.getLivemode());assertEquals(fixture.ownedRunId(),value.getMetadata().get("recovery_test_run"));assertEquals(url+"/v1/webhook/stripe",value.getUrl());assertEquals("2023-10-16",value.getApiVersion());}
    @Override public void close()throws Exception{
        try{
            if(endpoint==null&&createPending){
                for(var candidate:WebhookEndpoint.list(Map.of("limit",100L)).autoPagingIterable())
                    if(fixture.ownedRunId().equals(candidate.getMetadata().get("recovery_test_run"))){owned(candidate);if(endpoint!=null)throw new IllegalStateException("MULTIPLE_OWNED_PUBLIC_ENDPOINTS");endpoint=candidate;}
                if(endpoint==null){fixture.evidence("public-webhook-cleanup",Map.of("result","UNKNOWN","reason","CREATE_RESPONSE_UNKNOWN"));throw new IllegalStateException("PUBLIC_ENDPOINT_CREATE_UNKNOWN_FOLLOW_JOURNAL");}
            }
            if(endpoint!=null){
                owned(WebhookEndpoint.retrieve(endpoint.getId()));String key=fixture.ownedRunId()+":delete-public-webhook:"+endpoint.getId();
                fixture.evidence("public-webhook-delete-intent",Map.of("endpointId",endpoint.getId(),"idempotencyKey",key));
                var deleted=endpoint.delete(options(key));assertEquals(Boolean.TRUE,deleted.getDeleted());
                fixture.evidence("public-webhook-cleanup",Map.of("endpointId",endpoint.getId(),"result","PASS","deleted",true));endpoint=null;createPending=false;
            }
        }finally{
            if(tunnel!=null){tunnel.destroy();if(!tunnel.waitFor(5,TimeUnit.SECONDS))tunnel.destroyForcibly();}
            proxy.close();
        }
    }
}
