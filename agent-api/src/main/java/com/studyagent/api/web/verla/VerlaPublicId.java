package com.studyagent.api.web.verla;

import com.studyagent.common.verla.id.VerlaPublicIdType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 path / query 参数为 V2 public id，由 {@link VerlaPublicIdArgumentResolver} 解码为内部 Long。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface VerlaPublicId {

    VerlaPublicIdType value();
}
