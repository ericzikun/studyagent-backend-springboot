package com.studyagent.infra.service.billing;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Test-only explicit approval of one exact provider envelope; never sends mail. */
final class RecoverySandboxMailApproval {
    record Proposal(String runId,long rootId,long actionId,String invoiceId,String idempotencyKey,String frozenBody,Instant expiresAt){}
    static boolean withinWindow(Proposal proposal,Instant now,long monotonicDeadline,long monotonicNow){
        return now.isBefore(proposal.expiresAt()) && monotonicNow-monotonicDeadline<0;
    }
    static boolean sameEnvelope(String expected,String persisted){
        if(expected==null||persisted==null)return false;
        try{
            var approved=JsonParser.parseString(expected);var stored=JsonParser.parseString(persisted);
            return approved.isJsonObject()&&stored.isJsonObject()&&approved.equals(stored);
        }catch(com.google.gson.JsonParseException e){return false;}
    }
    static String bodySha256(String body){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE");}
    }
    static ChallengeFile writeChallenge(Path path,Proposal proposal)throws Exception {
        var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),permissions)){
            var values=new LinkedHashMap<String,Object>();values.put("runId",proposal.runId());values.put("rootId",proposal.rootId());values.put("actionId",proposal.actionId());values.put("invoiceId",proposal.invoiceId());
            values.put("idempotencyKey",proposal.idempotencyKey());values.put("frozenBody",proposal.frozenBody());values.put("bodySha256",bodySha256(proposal.frozenBody()));values.put("expiresAt",proposal.expiresAt().toString());
            values.put("approvalState","NOT_APPROVED");values.put("stripeMode","TEST");values.put("database","ISOLATED_LOCAL_SCHEMA");values.put("maximumEmails",1);values.put("pageE2E","NOT_RUN");
            var bytes=ByteBuffer.wrap(new Gson().toJson(values).getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);
        }
        return new ChallengeFile(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
    }
    static boolean approved(Path path,Proposal proposal,Instant now)throws Exception {
        if(!now.isBefore(proposal.expiresAt())||!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>16384||!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))return false;
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
            var bytes=ByteBuffer.allocate(16385);while(bytes.hasRemaining()&&channel.read(bytes)!=-1){}
            if(!bytes.hasRemaining())return false;bytes.flip();var json=JsonParser.parseString(StandardCharsets.UTF_8.decode(bytes).toString()).getAsJsonObject();
            if(!Objects.equals(before.fileKey(),Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()))return false;
            var flag=json.get("approved");return flag!=null&&flag.isJsonPrimitive()&&flag.getAsJsonPrimitive().isBoolean()&&flag.getAsBoolean()
                    &&proposal.runId().equals(json.get("runId").getAsString())&&bodySha256(proposal.frozenBody()).equals(json.get("bodySha256").getAsString())
                    &&proposal.idempotencyKey().equals(json.get("idempotencyKey").getAsString());
        }catch(com.google.gson.JsonParseException|IllegalStateException|NullPointerException e){return false;}
    }
    static final class ChallengeFile implements AutoCloseable {
        private final Path path;private final Object fileKey;
        ChallengeFile(Path path,Object fileKey){this.path=path;this.fileKey=Objects.requireNonNull(fileKey);}
        @Override public void close()throws Exception {
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&fileKey.equals(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()))Files.delete(path);
        }
    }
}
