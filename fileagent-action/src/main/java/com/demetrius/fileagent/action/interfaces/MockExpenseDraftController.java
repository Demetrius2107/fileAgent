package com.demetrius.fileagent.action.interfaces;

import com.demetrius.fileagent.action.application.MockExpenseDraftService;
import com.demetrius.fileagent.action.application.MockExpenseDraftService.DraftResult;
import com.demetrius.fileagent.action.application.MockExpenseDraftService.IdempotencyConflictException;
import com.demetrius.fileagent.common.result.ApiResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 默认关闭的本地报销系统模拟接口，仅供动作链路验收。
 *
 * @author raosaijie
 */
@RestController
@RequestMapping("/internal/action-mock/v1/expense-drafts")
@ConditionalOnProperty(prefix = "fileagent.action.mock", name = "enabled", havingValue = "true")
public class MockExpenseDraftController {

    private final MockExpenseDraftService service;
    private final byte[] expectedToken;

    public MockExpenseDraftController(MockExpenseDraftService service,
                                      @Value("${fileagent.action.mock.token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("启用动作 Mock 时必须配置 FILEAGENT_ACTION_MOCK_TOKEN");
        }
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /** 创建或重放草稿；必须提供 Bearer Token 和幂等键。 */
    @PostMapping
    public ResponseEntity<ApiResult<?>> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody CreateRequest request) {
        if (!authorized(authorization)) {
            return response(HttpStatus.UNAUTHORIZED, ApiResult.fail(401, "认证失败"));
        }
        try {
            DraftResult result = service.create(key, request.amount(), request.memo(), request.bankAccount());
            return response(result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED, ApiResult.ok(result));
        } catch (IdempotencyConflictException e) {
            return response(HttpStatus.CONFLICT, ApiResult.fail(409, "幂等键已用于不同请求"));
        } catch (IllegalArgumentException e) {
            return response(HttpStatus.BAD_REQUEST, ApiResult.fail(400, e.getMessage()));
        }
    }

    /** 根据草稿 ID 查询已创建的测试草稿。 */
    @GetMapping("/{draftId}")
    public ResponseEntity<ApiResult<?>> find(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String draftId) {
        if (!authorized(authorization)) {
            return response(HttpStatus.UNAUTHORIZED, ApiResult.fail(401, "认证失败"));
        }
        return service.find(draftId)
                .<ResponseEntity<ApiResult<?>>>map(result -> response(HttpStatus.OK, ApiResult.ok(result)))
                .orElseGet(() -> response(HttpStatus.NOT_FOUND, ApiResult.fail(404, "草稿不存在")));
    }

    private boolean authorized(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(expectedToken,
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<ApiResult<?>> response(HttpStatus status, ApiResult<?> body) {
        return ResponseEntity.status(status).header("X-Request-Id", UUID.randomUUID().toString()).body(body);
    }

    /** Mock 创建请求；账户字段不会出现在响应或持久化表中。 */
    public record CreateRequest(BigDecimal amount, String memo, String bankAccount) {
    }
}
