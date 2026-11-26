package com.studyagent.service.application.demo;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.billing.BillingDomainService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoteAccessServiceTest {

    private final BillingDomainService billing = mock(BillingDomainService.class);
    private final VerlaQuotaService quota = mock(VerlaQuotaService.class);
    private final NoteAccessService service = new NoteAccessService(billing, quota);

    @Test
    void paidMemberCanUseNotes() {
        when(billing.isPaidMember("user_1")).thenReturn(true);
        assertDoesNotThrow(() -> service.assertCanUseNotes("user_1"));
    }

    @Test
    void internalExemptAccountCanUseNotesWithoutSubscription() {
        when(quota.isCommercialExempt("admin_1")).thenReturn(true);
        assertDoesNotThrow(() -> service.assertCanUseNotes("admin_1"));
    }

    @Test
    void freeUserIsRejectedWithNoteMemberRequired() {
        BusinessException error = assertThrows(
                BusinessException.class, () -> service.assertCanUseNotes("free_1"));
        assertEquals(ApiCode.NOTE_MEMBER_REQUIRED.getCode(), error.getCode());
    }
}
