package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxFundsSetupTest {
    @TempDir Path directory;
    @Test void challengeIsPrivateAndRemovedWhenItsOwnerCloses() throws Exception {
        var path=directory.resolve("challenge.json");
        try(var ignored=RecoverySandboxFundsSetup.writeChallenge(path,"run-a","seti_test","https://hooks.stripe.com/test")){
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(path));
            assertTrue(Files.readString(path).contains("run-a"));
            assertThrows(FileAlreadyExistsException.class,()->RecoverySandboxFundsSetup.writeChallenge(path,"run-b","seti_other","https://hooks.stripe.com/other"));
        }
        assertFalse(Files.exists(path));
    }
    @Test void anotherOwnersReplacementIsNeverDeleted() throws Exception {
        var path=directory.resolve("challenge.json");
        var owner=RecoverySandboxFundsSetup.writeChallenge(path,"run-a","seti_test","https://hooks.stripe.com/test");
        Files.move(path,directory.resolve("original.json"));Files.writeString(path,"another run");
        owner.close();assertEquals("another run",Files.readString(path));
    }
    @Test void anExistingSymlinkIsNeverFollowedOrDeleted() throws Exception {
        var target=directory.resolve("existing");Files.writeString(target,"untouched");
        var path=directory.resolve("challenge.json");Files.createSymbolicLink(path,target);
        assertThrows(FileAlreadyExistsException.class,()->RecoverySandboxFundsSetup.writeChallenge(path,"run-a","seti_test","https://hooks.stripe.com/test"));
        assertEquals("untouched",Files.readString(target));assertTrue(Files.isSymbolicLink(path));
    }
}
