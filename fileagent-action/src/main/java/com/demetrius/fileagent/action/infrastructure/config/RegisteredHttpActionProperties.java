package com.demetrius.fileagent.action.infrastructure.config;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册式 HTTP 动作配置；真实 URL、凭证引用和传输限制只存在于服务端。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.action.http")
public class RegisteredHttpActionProperties {

    private boolean enabled;
    private List<Endpoint> endpoints = new ArrayList<>();

    /**
     * 单个预注册 Endpoint 的完整服务端定义。
     *
     * @author raosaijie
     * @since 0.1.0
     * @date 2026-10-20
     */
    @Getter
    @Setter
    public static class Endpoint {

        private String endpointId;
        private boolean mockEndpoint;
        private String displayName;
        private String definitionVersion;
        private String endpointRevision;
        private String policyVersion;
        private String method;
        private String baseUrl;
        private String pathTemplate;
        private Map<String, Object> requestSchema = new LinkedHashMap<>();
        private ActionRiskLevel riskLevel;
        private DataEgressPolicy dataEgressPolicy;
        private String resourceScope;
        private String safeSummary;
        private String expectedImpact;
        private String credentialRef;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);
        private Duration callTimeout = Duration.ofSeconds(15);
        private int maxResponseBytes = 256 * 1024;
        private Set<String> allowedContentTypes = new LinkedHashSet<>();
        private String idempotencyHeader;
    }

    /**
     * 数据外发边界：声明模型可写字段以及是否会把模型自由文本发送到外部系统。
     *
     * @author raosaijie
     * @since 0.1.0
     * @date 2026-10-20
     */
    @Getter
    @Setter
    public static class DataEgressPolicy {

        private Set<String> modelWritableFields = new LinkedHashSet<>();
        private boolean externalizesModelFreeText;
    }
}
