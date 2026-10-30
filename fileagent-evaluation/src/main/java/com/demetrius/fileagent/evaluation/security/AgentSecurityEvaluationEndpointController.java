package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.evaluation.EvaluationEndpointProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 仅用于受信部署环境的 Phase 3 模型攻击评测接口，默认不注册，也不面向浏览器公开。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/internal/evaluation/agent-security")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class AgentSecurityEvaluationEndpointController {

    /** 与现有评测接口一致的内部令牌请求头。 */
    public static final String TOKEN_HEADER = "X-FileAgent-Evaluation-Token";

    private final AgentSecurityEvaluationEndpointService endpointService;
    private final EvaluationEndpointProperties properties;

    /** 执行一轮内置攻击题库评测。 */
    @PostMapping("/run")
    public ResponseEntity<ApiResult<AgentSecurityEvaluationRunResponse>> run(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) AgentSecurityEvaluationRunRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, "评测接口认证失败"));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(endpointService.run(request)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, exception.getMessage()));
        }
    }

    private boolean tokenMatches(String providedToken) {
        if (providedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(properties.getToken().getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
