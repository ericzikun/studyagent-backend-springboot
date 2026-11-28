package com.studyagent.infra.entity.demo.note;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DemoNoteConversationEntityTest {

    /**
     * 提交/忽略提案时会把 pendingProposalJson 置 null；MyBatis-Plus 默认跳过 null 字段，
     * 不显式声明 ALWAYS 的话旧提案会永远留在行里（线上 vc_wKZcX / vc_Ro5jW 都因此复发）。
     */
    @Test
    void pendingProposalJson_isAlwaysUpdatedSoClearingPersists() throws Exception {
        Field field = DemoNoteConversationEntity.class.getDeclaredField("pendingProposalJson");
        TableField tableField = field.getAnnotation(TableField.class);

        assertNotNull(tableField);
        assertEquals("pending_proposal_json", tableField.value());
        assertEquals(FieldStrategy.ALWAYS, tableField.updateStrategy());
    }
}
