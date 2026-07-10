package com.studyagent.infra.repository.quota;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.QuotaVipUserEntity;
import com.studyagent.infra.mapper.QuotaVipUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaVipRepositoryImplTest {

    @Mock
    private QuotaVipUserMapper quotaVipUserMapper;

    private QuotaVipRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new QuotaVipRepositoryImpl(quotaVipUserMapper);
    }

    @Test
    void existsActiveVip_returnsFalse_forBlank() {
        assertFalse(repository.existsActiveVip(null));
        assertFalse(repository.existsActiveVip(" "));
        verifyNoInteractions(quotaVipUserMapper);
    }

    @Test
    void existsActiveVip_returnsTrue_whenCountPositive() {
        when(quotaVipUserMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertTrue(repository.existsActiveVip("user_vip"));
        ArgumentCaptor<Wrapper<QuotaVipUserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(quotaVipUserMapper).selectCount(captor.capture());
    }

    @Test
    void existsActiveVip_returnsFalse_whenCountZero() {
        when(quotaVipUserMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertFalse(repository.existsActiveVip("user_normal"));
    }
}
