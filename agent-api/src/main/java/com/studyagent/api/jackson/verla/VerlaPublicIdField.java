package com.studyagent.api.jackson.verla;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request-body {@code Long} field as accepting V2 public ids in JSON.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = VerlaPublicIdLongDeserializer.class)
public @interface VerlaPublicIdField {

    VerlaPublicIdType value();
}
