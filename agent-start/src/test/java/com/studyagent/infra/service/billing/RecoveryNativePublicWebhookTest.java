package com.studyagent.infra.service.billing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryNativePublicWebhookTest {
    @Test void ownedDeliveryPreservesExactBodyAndSignature()throws Exception {
        var seen=new AtomicReference<byte[]>();var signature=new AtomicReference<String>();
        var target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",x->{seen.set(x.getRequestBody().readAllBytes());signature.set(x.getRequestHeaders().getFirst("Stripe-Signature"));x.sendResponseHeaders(200,-1);x.close();});target.start();
        try(var proxy=new RecoveryNativePublicWebhook.OwnedProxy("cus_owned",URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/"))){
            String body=event("cus_owned");assertEquals(200,send(proxy.port(),body,"t=1,v1=original-signature"));
            assertArrayEquals(body.getBytes(StandardCharsets.UTF_8),seen.get());assertEquals("t=1,v1=original-signature",signature.get());assertEquals(1,proxy.deliveries().size());
        }finally{target.stop(0);}
    }
    @ParameterizedTest @ValueSource(strings={"cus_foreign",""})
    void unrelatedCustomersNeverReachApplication(String customer)throws Exception{
        var count=new AtomicInteger();var target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",x->{count.incrementAndGet();x.sendResponseHeaders(200,-1);x.close();});target.start();
        try(var proxy=new RecoveryNativePublicWebhook.OwnedProxy("cus_owned",URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/"))){
            assertEquals(200,send(proxy.port(),event(customer),"t=1,v1=original-signature"));assertEquals(0,count.get());assertTrue(proxy.deliveries().isEmpty());
        }finally{target.stop(0);}
    }
    @Test void malformedPayloadFailsWithoutForwarding()throws Exception{
        try(var proxy=new RecoveryNativePublicWebhook.OwnedProxy("cus_owned",URI.create("http://127.0.0.1:1/"))){
            assertEquals(400,send(proxy.port(),"not-json","t=1,v1=original-signature"));assertTrue(proxy.deliveries().isEmpty());
        }
    }
    @Test void oversizedPayloadFailsWithoutForwarding()throws Exception{
        try(var proxy=new RecoveryNativePublicWebhook.OwnedProxy("cus_owned",URI.create("http://127.0.0.1:1/"))){
            assertEquals(413,send(proxy.port(),"x".repeat(1024*1024+1),"t=1,v1=original-signature"));assertTrue(proxy.deliveries().isEmpty());
        }
    }
    @Test void applicationSignatureFailureIsNotAcknowledgedAsSuccess()throws Exception{
        var target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",x->{x.sendResponseHeaders(401,-1);x.close();});target.start();
        try(var proxy=new RecoveryNativePublicWebhook.OwnedProxy("cus_owned",URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/"))){
            assertEquals(401,send(proxy.port(),event("cus_owned"),"t=1,v1=invalid"));assertEquals(401,proxy.deliveries().get(0).status());
        }finally{target.stop(0);}
    }
    private static String event(String customer){return "{\"id\":\"evt_owned\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_owned\",\"customer\":\""+customer+"\"}}}";}
    private static int send(int port,String body,String signature)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/webhook/stripe")).header("Stripe-Signature",signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
