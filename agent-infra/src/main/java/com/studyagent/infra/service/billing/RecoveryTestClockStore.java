package com.studyagent.infra.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durable TEST operator intents. The directory must be a persistent volume shared by all TEST workers. */
final class RecoveryTestClockStore {
    static final class Operation {
        public String type,target,key,state="READY",requestId,errorCode;
        public Map<String,Object> parameters=new LinkedHashMap<>();
        public Instant submittedAt;
    }
    static final class Run {
        public String runId,userId,customerId,clockId,subscriptionId;
        public String invoiceId;
        public Instant observedBusinessTime;
        public Map<String,Operation> operations=new LinkedHashMap<>();
        public RecoveryOwnedClockEvidence.Frame proof;
        public RecoveryTestClockStripe.Capture before;
    }
    private static final Map<Path,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private final Path directory;
    private final ReentrantLock lock;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    RecoveryTestClockStore(Path directory)throws Exception {
        if(!directory.isAbsolute()||Files.isSymbolicLink(directory))throw new IllegalArgumentException("Absolute nonsymlink proof directory required");
        Files.createDirectories(directory);this.directory=directory.toRealPath();
        lock=LOCKS.computeIfAbsent(this.directory,ignored->new ReentrantLock(true));
    }
    <T>T locked(Callable<T> task) {
        if(lock.isHeldByCurrentThread())return call(task);
        lock.lock();
        try(var channel=FileChannel.open(directory.resolve("operator.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var ignored=channel.lock()){return call(task);}
        catch(RuntimeException e){throw e;}
        catch(Exception e){throw new IllegalStateException("RECOVERY_TEST_CLOCK_STORE_UNAVAILABLE",e);}
        finally{lock.unlock();}
    }
    private static <T>T call(Callable<T> task){try{return task.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("RECOVERY_TEST_CLOCK_OPERATION_UNAVAILABLE",e);}}
    static void runId(String id){if(id==null||!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{7,79}"))throw new IllegalArgumentException("Invalid TEST run ID");}
    List<Run> bindings(){
        try(var files=Files.list(directory)){
            var paths=files.filter(p->p.getFileName().toString().endsWith(".json")).limit(1001).toList();
            if(paths.size()>1000)throw new IllegalStateException("Too many TEST runs; archive retired runs first");
            var runs=new ArrayList<Run>();for(var path:paths){String name=path.getFileName().toString();var run=read(name.substring(0,name.length()-5));if(run!=null)runs.add(run);}return runs;
        }catch(java.io.IOException e){throw new IllegalStateException("TEST bindings unavailable",e);}
    }
    Run read(String id) {
        runId(id);var path=directory.resolve(id+".json");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Invalid proof file");
        try{var run=json.readValue(Files.readAllBytes(path),Run.class);if(!id.equals(run.runId))throw new IllegalStateException("Conflicting proof ID");return run;}
        catch(Exception e){throw new IllegalStateException("RECOVERY_TEST_CLOCK_PROOF_UNREADABLE",e);}
    }
    void save(Run run) {
        runId(run.runId);
        if(!lock.isHeldByCurrentThread())throw new IllegalStateException("Proof writes require operator lock");
        Path temporary=null;
        try{
            byte[] bytes=json.writeValueAsBytes(run);temporary=Files.createTempFile(directory,run.runId+"-",".pending");
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
            try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}
            Files.move(temporary,directory.resolve(run.runId+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temporary=null;
            try(var parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}
            var states=new LinkedHashMap<String,String>();run.operations.forEach((key,value)->states.put(key,value.state));
            byte[] audit=(json.writeValueAsString(Map.of("at",Instant.now(),"runId",run.runId,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"operations",states))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var file=FileChannel.open(directory.resolve(run.runId+".audit.jsonl"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)){var buffer=java.nio.ByteBuffer.wrap(audit);while(buffer.hasRemaining())file.write(buffer);file.force(true);}
        }catch(Exception e){throw new IllegalStateException("RECOVERY_TEST_CLOCK_PROOF_NOT_DURABLE",e);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
}
