package com.studyagent.infra.testutil;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

public final class MybatisPlusTableInfoTestHelper {

    private MybatisPlusTableInfoTestHelper() {
    }

    public static void initTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) {
            return;
        }
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), entityType.getName());
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
