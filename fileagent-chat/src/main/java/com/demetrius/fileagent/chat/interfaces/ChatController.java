package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatResp;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 对话接口（骨架声明，方法体由协作者实现，M1 同步 / M2 SSE 流式）。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/chat")
@Tag(name = "对话")
public class ChatController {

    @PostMapping
    public ApiResult<ChatResp> chat(@PathVariable Long sessionId,
                                    @RequestBody ChatReq req) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }
}
