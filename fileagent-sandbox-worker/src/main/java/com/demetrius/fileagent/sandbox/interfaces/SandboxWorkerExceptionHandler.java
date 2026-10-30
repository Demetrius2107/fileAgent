package com.demetrius.fileagent.sandbox.interfaces;

import com.demetrius.fileagent.common.result.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Worker 错误出口：向调用方返回稳定错误，不泄露请求代码、容器命令或运行时堆栈。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@RestControllerAdvice
public class SandboxWorkerExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResult<Void>> handleBadRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiResult.fail(400, "Sandbox 请求不合法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(500, "Sandbox Worker 执行失败"));
    }
}
