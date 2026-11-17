package com.studyagent.infra.service.billing;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxMailApprovalTest {
    @TempDir Path directory;
    private final Instant now=Instant.parse("2026-11-17T00:00:00Z");
    private RecoverySandboxMailApproval.Proposal proposal(){return new RecoverySandboxMailApproval.Proposal("run-a",7,8,"in_test","recovery:in_test:manual-email","{\"from\":\"sender\",\"to\":[\"owned@example.invalid\"],\"text\":\"exact body\"}",now.plusSeconds(900));}
    @Test void persistedEnvelopeAllowsJsonNormalizationButRejectsAnyEnvelopeChange(){
        String expected="{\"from\":\"sender@example.invalid\",\"to\":[\"one@example.invalid\",\"two@example.invalid\"],\"subject\":\"Approved subject\",\"text\":\"exact body\\nnext line\"}";
        String normalized="{ \"text\": \"exact body\\nnext line\", \"to\": [\"one@example.invalid\", \"two@example.invalid\"], \"from\": \"sender@example.invalid\", \"subject\": \"Approved subject\" }";
        assertTrue(RecoverySandboxMailApproval.sameEnvelope(expected,normalized),"SQL JSON normalization must retain the approved envelope");
        assertTrue(RecoverySandboxMailApproval.sameEnvelope(expected,expected));
        for(String changed:new String[]{
                expected.replace("sender@example.invalid","other@example.invalid"),
                expected.replace("one@example.invalid","other@example.invalid"),
                expected.replace("[\"one@example.invalid\",\"two@example.invalid\"]","[\"two@example.invalid\",\"one@example.invalid\"]"),
                expected.replace("Approved subject","Changed subject"),
                expected.replace("exact body","changed body"),
                expected.replace("}",",\"bcc\":[\"hidden@example.invalid\"]}"),
                expected.replace("\"subject\":\"Approved subject\",",""),
                "{}","{broken","null","[]",""
        })assertFalse(RecoverySandboxMailApproval.sameEnvelope(expected,changed),"Persisted envelope changes must be rejected");
        assertFalse(RecoverySandboxMailApproval.sameEnvelope("{broken",expected));
        assertFalse(RecoverySandboxMailApproval.sameEnvelope("{broken","{broken"));
        assertFalse(RecoverySandboxMailApproval.sameEnvelope(null,expected));
        assertFalse(RecoverySandboxMailApproval.sameEnvelope(expected,null));
    }
    @Test void challengeContainsExactEnvelopeAndStableBindingAndIsPrivate()throws Exception {
        var p=proposal();var path=directory.resolve("challenge.json");
        try(var ignored=RecoverySandboxMailApproval.writeChallenge(path,p)){
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(path));
            var json=new Gson().fromJson(Files.readString(path),Map.class);
            assertEquals(p.frozenBody(),json.get("frozenBody"));assertEquals(p.runId(),json.get("runId"));
            assertEquals(p.idempotencyKey(),json.get("idempotencyKey"));assertEquals(RecoverySandboxMailApproval.bodySha256(p.frozenBody()),json.get("bodySha256"));
            assertEquals("NOT_APPROVED",json.get("approvalState"));
            assertThrows(FileAlreadyExistsException.class,()->RecoverySandboxMailApproval.writeChallenge(path,p));
        }
        assertFalse(Files.exists(path));
    }
    @Test void missingApprovalNeverApproves()throws Exception {assertFalse(RecoverySandboxMailApproval.approved(directory.resolve("absent"),proposal(),now));}
    @Test void approvalRequiresExplicitTrueAndExactRunHashAndKeyAndUnexpiredProposal()throws Exception {
        var p=proposal();var path=directory.resolve("approval.json");
        var correct=Map.<String,Object>of("approved",true,"runId",p.runId(),"bodySha256",RecoverySandboxMailApproval.bodySha256(p.frozenBody()),"idempotencyKey",p.idempotencyKey());
        Files.writeString(path,new Gson().toJson(correct));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        assertTrue(RecoverySandboxMailApproval.approved(path,p,now));assertFalse(RecoverySandboxMailApproval.approved(path,p,p.expiresAt()));
        for(String field:correct.keySet()){
            var changed=new java.util.HashMap<String,Object>(correct);changed.put(field,field.equals("approved")?false:"wrong");Files.writeString(path,new Gson().toJson(changed));
            assertFalse(RecoverySandboxMailApproval.approved(path,p,now),field);
        }
    }
    @Test void malformedOrPublicOrSymlinkApprovalNeverApproves()throws Exception {
        var path=directory.resolve("approval.json");Files.writeString(path,"broken");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        assertFalse(RecoverySandboxMailApproval.approved(path,proposal(),now));
        Files.writeString(path,"{}");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));assertFalse(RecoverySandboxMailApproval.approved(path,proposal(),now));
        var link=directory.resolve("link.json");Files.createSymbolicLink(link,path);assertFalse(RecoverySandboxMailApproval.approved(link,proposal(),now));
    }
    @Test void replacementIsNotDeletedAndChallengeSymlinkIsNeverFollowed()throws Exception {
        var path=directory.resolve("challenge.json");var owner=RecoverySandboxMailApproval.writeChallenge(path,proposal());
        Files.move(path,directory.resolve("original"));Files.writeString(path,"replacement");owner.close();assertEquals("replacement",Files.readString(path));
        var link=directory.resolve("link");Files.createSymbolicLink(link,path);assertThrows(FileAlreadyExistsException.class,()->RecoverySandboxMailApproval.writeChallenge(link,proposal()));assertEquals("replacement",Files.readString(path));
    }
    @Test void approvalWindowExpiresByMonotonicTimeEvenWhenWallClockRemainsBeforeExpiry(){
        var p=proposal();long deadline=900_000_000_000L;
        assertTrue(RecoverySandboxMailApproval.withinWindow(p,now,deadline,deadline-1));
        assertFalse(RecoverySandboxMailApproval.withinWindow(p,now.minusSeconds(60),deadline,deadline),"A wall clock rollback must not extend approval beyond the monotonic deadline");
        assertFalse(RecoverySandboxMailApproval.withinWindow(p,now,deadline,deadline+1));
        assertFalse(RecoverySandboxMailApproval.withinWindow(p,p.expiresAt(),deadline,deadline-1));
    }
    @Test void shaIsExactUtf8AndChangesWithEnvelope(){
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",RecoverySandboxMailApproval.bodySha256("abc"));
        assertNotEquals(RecoverySandboxMailApproval.bodySha256("abc"),RecoverySandboxMailApproval.bodySha256("abc\n"));
    }
}
