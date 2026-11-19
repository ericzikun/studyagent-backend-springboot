package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTestClockStoreTest {
    @TempDir Path directory;
    @Test void unknownIntentSurvivesRestartWithoutBecomingReady()throws Exception{
        var store=new RecoveryTestClockStore(directory);var run=new RecoveryTestClockStore.Run();run.runId="recovery-store-unit";
        var op=new RecoveryTestClockStore.Operation();op.state="UNKNOWN";op.key="frozen-idempotency";op.parameters=Map.of("off_session",true);run.operations.put("pay",op);
        store.locked(()->{store.save(run);return null;});var restored=new RecoveryTestClockStore(directory).read(run.runId).operations.get("pay");
        assertEquals("UNKNOWN",restored.state);assertEquals(op.key,restored.key);assertEquals(op.parameters,restored.parameters);assertFalse(Files.readString(directory.resolve(run.runId+".audit.jsonl")).isBlank());
    }
    @Test void completeProofAndCaptureSurviveRestartAndRevalidate()throws Exception{
        var proof=RecoveryOwnedClockEvidenceTest.persistableProof();var store=new RecoveryTestClockStore(directory);var run=new RecoveryTestClockStore.Run();run.runId="recovery-proof-unit";run.proof=proof;
        run.before=new RecoveryTestClockStripe.Capture(proof.beforeClock(),proof.beforeSubscription(),proof.beforeInvoiceIds(),proof.beforeFormalInvoiceIds(),proof.usedPriceIds(),proof.preAttempt(),java.time.Instant.now());
        store.locked(()->{store.save(run);return null;});var restored=new RecoveryTestClockStore(directory).read(run.runId);
        assertEquals(proof,restored.proof);assertEquals(proof.beforeClock().frozenTime(),new RecoveryOwnedClockEvidence.Registry().register(restored.proof).occurredAt());assertEquals(run.before,restored.before);
    }
    @Test void concurrentCleanupCannotExposeANullBinding()throws Exception{
        var store=org.mockito.Mockito.spy(new RecoveryTestClockStore(directory));java.nio.file.Files.writeString(directory.resolve("recovery-removed.json"),"{}");
        // read() legitimately returns null when the file vanished after directory enumeration.
        org.mockito.Mockito.doReturn(null).when(store).read("recovery-removed");assertTrue(store.bindings().isEmpty());
    }
    @Test void writesRequireTheSharedLock()throws Exception{var store=new RecoveryTestClockStore(directory);var run=new RecoveryTestClockStore.Run();run.runId="recovery-store-unit";assertThrows(IllegalStateException.class,()->store.save(run));}
    @Test void nestedCallsReuseTheSameLock()throws Exception{var store=new RecoveryTestClockStore(directory);assertEquals(7,store.locked(()->store.locked(()->7)));}
    @Test void anotherWorkerCannotEnterWhileClockAdvanceOwnsTheLock()throws Exception{
        var first=new RecoveryTestClockStore(directory);var second=new RecoveryTestClockStore(directory);
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var entered=new java.util.concurrent.atomic.AtomicBoolean();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var advance=pool.submit(()->first.locked(()->{started.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return null;}));assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var worker=pool.submit(()->second.locked(()->{entered.set(true);return null;}));Thread.sleep(100);assertFalse(entered.get());release.countDown();advance.get(3,java.util.concurrent.TimeUnit.SECONDS);worker.get(3,java.util.concurrent.TimeUnit.SECONDS);assertTrue(entered.get());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void traversalAndSymlinkFilesAreRejected()throws Exception{
        var store=new RecoveryTestClockStore(directory);assertThrows(IllegalArgumentException.class,()->store.read("../outside"));
        Path external=Files.createTempFile("test-clock-outside-",".json");try{Files.createSymbolicLink(directory.resolve("recovery-symlink.json"),external);assertThrows(IllegalStateException.class,()->store.read("recovery-symlink"));}finally{Files.deleteIfExists(external);}
    }
}
