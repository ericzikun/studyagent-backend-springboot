package com.studyagent.infra.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBatisConfigTest {

    @Test
    void mybatisPlusInterceptor_registersOptimisticLockerAndPagination() {
        MybatisPlusInterceptor interceptor = new MyBatisConfig().mybatisPlusInterceptor();

        assertTrue(interceptor.getInterceptors().stream()
                .anyMatch(PaginationInnerInterceptor.class::isInstance));
        assertTrue(interceptor.getInterceptors().stream()
                .anyMatch(OptimisticLockerInnerInterceptor.class::isInstance));
    }
}
