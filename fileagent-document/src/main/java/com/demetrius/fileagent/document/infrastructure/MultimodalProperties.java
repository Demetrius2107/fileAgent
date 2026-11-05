package com.demetrius.fileagent.document.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档型多模态基础配置；页面数量、像素和总字节均为渲染前后的硬限制。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.multimodal")
public class MultimodalProperties {

    private boolean enabled = false;

    private int renderDpi = 120;

    private int maxPages = 100;

    private long maxPagePixels = 4_000_000;

    private long maxTotalAssetBytes = 67_108_864;
}
