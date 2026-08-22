package com.demetrius.fileagent.session.interfaces;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理接口（骨架声明，方法体由协作者实现，M1）。
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "会话管理")
public class SessionController {

    @PostMapping
    public ApiResult<SessionDto> create(@RequestBody CreateSessionReq req) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }

    @GetMapping
    public ApiResult<List<SessionDto>> list() {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }

    @GetMapping("/{id}/messages")
    public ApiResult<List<MessageDto>> messages(@PathVariable Long id) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }
}
