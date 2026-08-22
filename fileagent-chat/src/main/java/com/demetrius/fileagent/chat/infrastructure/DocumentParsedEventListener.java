package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.api.event.DocumentParsedEvent;

/**
 * 文档解析完成事件监听器（骨架声明，由协作者实现索引建立逻辑，M1）。
 * 订阅 document 域的 DocumentParsedEvent，把新文档片段写入检索索引。
 */
public class DocumentParsedEventListener {

    public void onParsed(DocumentParsedEvent event) {
        throw new UnsupportedOperationException("M1: 由协作者实现索引更新");
    }
}
