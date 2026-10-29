package com.studyagent.service.application.verla.clone;

/** 把生成的 SVG 存成可回放的 URL；实现放在 infra，service 只依赖这个接口。 */
public interface CloneAssetStore {

    /** 返回公开 URL；未启用/失败返回 null。 */
    String storeSvg(String svg, String name);
}
