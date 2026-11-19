package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTestClockPropertiesTest {
    @TempDir Path directory;
    private RecoveryTestClockProperties properties() {
        var p=new RecoveryTestClockProperties();p.setEnabled(true);p.setDirectory(directory.toString());
        p.setOperatorToken("test-operator-token-at-least-thirty-two-characters");p.setAllowedUserIds(Set.of("user_test"));return p;
    }
    private MockEnvironment testEnvironment(){return new MockEnvironment().withProperty("spring.profiles.active","recovery-test-clock");}
    @Test void explicitTestProfileAndTestKeyPermitTheOperator(){assertDoesNotThrow(()->properties().validate(testEnvironment(),"sk_test_independent_fixture"));}
    @Test void restrictedTestKeyIsPermitted(){assertDoesNotThrow(()->properties().validate(testEnvironment(),"rk_test_independent_fixture"));}
    @Test void defaultIsOff(){assertFalse(new RecoveryTestClockProperties().isEnabled());}
    @Test void absentTestProfileIsRejected(){assertThrows(IllegalStateException.class,()->properties().validate(new MockEnvironment(),"sk_test_fixture"));}
    @Test void productionProfileCannotBeCombinedWithTestProfile(){var env=testEnvironment();env.setActiveProfiles("prod","recovery-test-clock");assertThrows(IllegalStateException.class,()->properties().validate(env,"sk_test_fixture"));}
    @Test void liveKeyIsRejected(){assertThrows(IllegalStateException.class,()->properties().validate(testEnvironment(),"sk_live_do_not_use"));}
    @Test void missingKeyIsRejected(){assertThrows(IllegalStateException.class,()->properties().validate(testEnvironment(),null));}
    @Test void emptyOrWildcardUserListIsRejected(){var p=properties();p.setAllowedUserIds(Set.of());assertThrows(IllegalStateException.class,()->p.validate(testEnvironment(),"sk_test_fixture"));p.setAllowedUserIds(Set.of("*"));assertThrows(IllegalStateException.class,()->p.validate(testEnvironment(),"sk_test_fixture"));}
    @Test void relativeDirectoryIsRejected(){var p=properties();p.setDirectory("storage/test-clocks");assertThrows(IllegalStateException.class,()->p.validate(testEnvironment(),"sk_test_fixture"));}
    @Test void unboundedTokenIsRejected(){var p=properties();p.setOperatorToken("x".repeat(257));assertThrows(IllegalStateException.class,()->p.validate(testEnvironment(),"sk_test_fixture"));}
    @Test void weakTokenIsRejected(){var p=properties();p.setOperatorToken("short");assertThrows(IllegalStateException.class,()->p.validate(testEnvironment(),"sk_test_fixture"));}
}
