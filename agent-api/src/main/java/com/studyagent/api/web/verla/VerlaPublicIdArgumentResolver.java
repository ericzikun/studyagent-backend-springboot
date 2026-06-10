package com.studyagent.api.web.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 将 URL 中的 public id（{@code vc_xxx} 或迁移期纯数字）解析为内部 Long 主键。
 */
public class VerlaPublicIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(com.studyagent.api.web.verla.VerlaPublicId.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return Long.class.equals(type) || long.class.equals(type);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        com.studyagent.api.web.verla.VerlaPublicId annotation =
                parameter.getParameterAnnotation(com.studyagent.api.web.verla.VerlaPublicId.class);
        if (annotation == null) {
            throw new IllegalStateException("@VerlaPublicId missing");
        }

        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        String name = resolvePathVariableName(parameter, pathVariable);

        @SuppressWarnings("unchecked")
        Map<String, String> uriTemplateVars = (Map<String, String>) webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                NativeWebRequest.SCOPE_REQUEST);
        if (uriTemplateVars == null || !uriTemplateVars.containsKey(name)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "Missing path variable: " + name);
        }

        String raw = uriTemplateVars.get(name);
        try {
            long internalId = VerlaPublicIdCodec.requireInternalId(annotation.value(), raw);
            return parameter.getParameterType().equals(long.class) ? internalId : Long.valueOf(internalId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiCode.PARAM_ERROR, ex.getMessage());
        }
    }

    private static String resolvePathVariableName(MethodParameter parameter, PathVariable pathVariable) {
        if (pathVariable != null && !pathVariable.value().isBlank()) {
            return pathVariable.value();
        }
        if (pathVariable != null && !pathVariable.name().isBlank()) {
            return pathVariable.name();
        }
        String paramName = parameter.getParameterName();
        if (paramName == null || paramName.isBlank()) {
            throw new IllegalStateException("Cannot resolve @PathVariable name for @VerlaPublicId parameter");
        }
        return paramName;
    }
}
