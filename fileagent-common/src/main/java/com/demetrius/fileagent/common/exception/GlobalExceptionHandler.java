package com.demetrius.fileagent.common.exception;

import com.demetrius.fileagent.common.result.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        // 业务码 404 映射 HTTP 404，403 映射 HTTP 403，其余参数类业务错误保持 HTTP 400
        HttpStatus status;
        if (e.getCode() == 404) {
            status = HttpStatus.NOT_FOUND;
        } else if (e.getCode() == 403) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(ApiResult.fail(e.getCode(), e.getMessage()));
    }

    /**
     * SSE 客户端主动断开（停止生成/切换会话/刷新页面）后，异步写入必然失败：
     * 记 INFO 即可，不再走兜底 500——响应已不可写，向其写 ApiResult 只会产生二次异常噪音。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.info("SSE 客户端已断开，停止写入: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResult.fail(500, e.getMessage()));
    }
}
