# FileAgent 使用手册

FileAgent 支持 TXT、Markdown、PDF、DOCX、XLSX 和 CSV 文件。单个文件最大 50 MB，一次请求最大 100 MB。

XLSX 文件按工作表和结构化区域解析并建立索引。知识检索同时使用 BM25 关键词检索和向量检索，再通过 RRF 融合排序。回答会返回命中的来源文件名，便于用户核对证据。
