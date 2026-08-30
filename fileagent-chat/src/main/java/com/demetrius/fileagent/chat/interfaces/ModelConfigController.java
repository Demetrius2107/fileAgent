package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ModelProviderSummary;
import com.demetrius.fileagent.api.dto.SaveModelProviderReq;
import com.demetrius.fileagent.chat.application.ModelConfigAppService;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型 Provider 配置接口：前端「模型设置」面板的 CRUD 与启用/测试。
 * API Key 只进不出（落库密文、列表掩码），切换启用即时生效。
 */
@Slf4j
@RestController
@RequestMapping("/api/model-providers")
@Tag(name = "模型配置")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigAppService modelConfigAppService;

    @GetMapping
    public ApiResult<List<ModelProviderSummary>> list() {
        return ApiResult.ok(modelConfigAppService.list());
    }

    @PostMapping
    public ApiResult<ModelProviderSummary> save(@RequestBody SaveModelProviderReq req) {
        return ApiResult.ok(modelConfigAppService.save(req));
    }

    @PutMapping("/{id}/activate")
    public ApiResult<Boolean> activate(@PathVariable Long id) {
        modelConfigAppService.activate(id);
        return ApiResult.ok(true);
    }

    @PostMapping("/{id}/test")
    public ApiResult<String> test(@PathVariable Long id) {
        return ApiResult.ok(modelConfigAppService.test(id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        modelConfigAppService.delete(id);
        return ApiResult.ok(true);
    }
}
