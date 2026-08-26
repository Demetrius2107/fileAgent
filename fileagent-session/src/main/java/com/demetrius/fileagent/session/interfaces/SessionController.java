package com.demetrius.fileagent.session.interfaces;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.session.application.SessionAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理接口：仅做请求转发与 ApiResult 包装，业务在应用服务。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sessions")
@Tag(name = "会话管理")
public class SessionController {

    private final SessionAppService sessionAppService;

    @PostMapping
    public ApiResult<SessionDto> create(@RequestBody CreateSessionReq req) {
        return ApiResult.ok(sessionAppService.createSession(req));
    }

    @GetMapping
    public ApiResult<List<SessionDto>> list() {
        return ApiResult.ok(sessionAppService.listSessions());
    }

    @GetMapping("/{id}/messages")
    public ApiResult<List<MessageDto>> messages(@PathVariable Long id) {
        return ApiResult.ok(sessionAppService.listMessages(id));
    }
}
