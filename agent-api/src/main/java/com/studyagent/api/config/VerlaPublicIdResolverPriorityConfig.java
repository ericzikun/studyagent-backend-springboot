package com.studyagent.api.config;

import com.studyagent.api.web.verla.VerlaPublicIdArgumentResolver;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 让 {@link VerlaPublicIdArgumentResolver} 优先于内置的 {@code PathVariableMethodArgumentResolver} 执行。
 *
 * <p>背景：通过 {@code WebMvcConfigurer#addArgumentResolvers} 注册的自定义解析器会被排在内置解析器之后。
 * 而内置 {@code @PathVariable} 解析器会先命中 {@code @PathVariable Long cid} 参数，直接把 {@code vc_xxx}
 * 这类 public id 当作数字去 {@code Long.parseLong}，抛出 {@code NumberFormatException}，导致接口 500。
 *
 * <p>这里在 {@link RequestMappingHandlerAdapter} 初始化完成后，把自定义解析器移动到解析器列表最前，
 * 使带 {@code @VerlaPublicId} 的参数先被解码为内部 Long 主键；其余参数仍由内置解析器处理。
 */
@Configuration
public class VerlaPublicIdResolverPriorityConfig {

    private final RequestMappingHandlerAdapter requestMappingHandlerAdapter;

    public VerlaPublicIdResolverPriorityConfig(RequestMappingHandlerAdapter requestMappingHandlerAdapter) {
        this.requestMappingHandlerAdapter = requestMappingHandlerAdapter;
    }

    @PostConstruct
    public void prioritizeVerlaPublicIdResolver() {
        List<HandlerMethodArgumentResolver> current = requestMappingHandlerAdapter.getArgumentResolvers();
        if (current == null) {
            return;
        }
        List<HandlerMethodArgumentResolver> reordered = new ArrayList<>(current.size() + 1);
        reordered.add(new VerlaPublicIdArgumentResolver());
        for (HandlerMethodArgumentResolver resolver : current) {
            // 去重：移除通过 addArgumentResolvers 注册、排在末尾的同类解析器，只保留置顶的这个。
            if (!(resolver instanceof VerlaPublicIdArgumentResolver)) {
                reordered.add(resolver);
            }
        }
        requestMappingHandlerAdapter.setArgumentResolvers(reordered);
    }
}
