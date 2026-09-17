package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.common.result.ApiResult;
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
 * 仅在显式开启后提供的内部 Agent 端到端评测接口。
 *
 * @author raosaijie
 */
@RestController
@RequestMapping("/internal/evaluation/agent")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class AgentEvaluationEndpointController {

    public static final String TOKEN_HEADER = "X-FileAgent-Evaluation-Token";

    private final AgentEvaluationEndpointService agentEvaluationEndpointService;
    private final EvaluationEndpointProperties properties;

    @PostMapping("/run")
    public ResponseEntity<ApiResult<AgentEvaluationRunResponse>> run(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) AgentEvaluationRunRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, "评测接口认证失败"));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(agentEvaluationEndpointService.run(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, e.getMessage()));
        }
    }

    private boolean tokenMatches(String providedToken) {
        if (providedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.getToken().getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
