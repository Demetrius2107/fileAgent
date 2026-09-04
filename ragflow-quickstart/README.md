# RAGFlow Java Quickstart

这是一个独立的 Spring Boot Maven 模块，用最少的代码调用 RAGFlow HTTP API。文档解析、分块、向量化、检索、Prompt 和模型回答全部由 RAGFlow 完成。

该模块已加入 `fileAgent` 根 `pom.xml` 的 `<modules>`，IDEA 加载根 Maven 工程后会显示为 `ragflow-quickstart` 模块；它不依赖其他 `fileagent-*` 业务模块，也可以在本目录单独运行。

## 1. 准备 RAGFlow

1. 登录 [RAGFlow Cloud](https://cloud.ragflow.io)。
2. 在 `Model providers` 中配置模型供应商，并设置默认 Chat 和 Embedding 模型。
3. 在头像菜单的 `API` 页面创建 API Key。
4. API Key 只放环境变量，不要写进 `application.yml` 或提交到 Git。

RAGFlow 当前官方接口说明：[HTTP API Reference](https://github.com/infiniflow/ragflow/blob/main/docs/references/http_api_reference.md)。

## 2. 启动

需要 JDK 21 和 Maven：

```bash
cd ragflow-quickstart
export RAGFLOW_BASE_URL="https://cloud.ragflow.io"
export RAGFLOW_API_KEY="你的 RAGFlow API Key"
mvn spring-boot:run
```

打开 <http://localhost:8091>。

如果 RAGFlow 账号 API 页面显示了不同的 API Server 地址，以页面提供的地址为准。`RAGFLOW_BASE_URL` 可以填写服务根地址，也可以直接填写以 `/api/v1` 结尾的地址。

## 3. 页面流程

1. 创建 Dataset，或者粘贴已有 Dataset ID。
2. 上传文件；示例会在上传成功后自动触发解析。文档状态变为 `DONE` 后继续。
3. 创建绑定该 Dataset 的 Chat Assistant，再创建 Session。
4. 发送问题；示例通过 SSE 展示 RAGFlow 的增量回答和引用来源。

浏览器只保存 Dataset、Chat 和 Session ID。RAGFlow API Key 始终保留在服务端环境变量中。

## 4. 示例调用的 RAGFlow API

| 操作 | RAGFlow API |
|---|---|
| 创建知识库 | `POST /api/v1/datasets` |
| 上传文档 | `POST /api/v1/datasets/{dataset_id}/documents` |
| 触发解析 | `POST /api/v1/datasets/{dataset_id}/chunks` |
| 查询文档 | `GET /api/v1/datasets/{dataset_id}/documents` |
| 创建助手 | `POST /api/v1/chats` |
| 创建会话 | `POST /api/v1/chats/{chat_id}/sessions` |
| 流式问答 | `POST /api/v1/chat/completions` |

## 5. 测试

```bash
mvn test
```

测试使用本地临时 HTTP Server，不会访问 RAGFlow Cloud，也不需要真实 API Key。
