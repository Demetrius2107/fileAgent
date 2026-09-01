package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ModelProviderSummary;
import com.demetrius.fileagent.api.dto.SaveModelProviderReq;

import java.util.List;

/**
 * 模型 Provider 配置用例：新增/列表/启用/删除/连通性测试。
 * Key 由 common 的 AesGcmCipher 加密落库，明文不出后端。
 */
public interface ModelConfigAppService {

    /** 全部配置概要（key 掩码），按创建时间倒序 */
    List<ModelProviderSummary> list();

    /** 新增配置（key 加密入库；库内无启用配置时本套自动启用） */
    ModelProviderSummary save(SaveModelProviderReq req);

    /** 启用指定配置并热切换聊天模型（原启用配置自动置为停用） */
    void activate(Long id);

    /** 删除配置；若删除的是启用中的配置，回落到环境变量默认模型 */
    void delete(Long id);

    /** 用存储的 key 做一次真实模型调用验证连通性，返回结果描述 */
    String test(Long id);
}
