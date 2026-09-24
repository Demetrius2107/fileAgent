package com.demetrius.fileagent.session.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话
 */
@Getter
@Setter
@Entity
@Table(name = "chat_session")
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话标题 */
    private String title;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 结构化滚动摘要 JSON。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String summary;

    /** 摘要已覆盖的最后一条消息 ID。 */
    private Long summaryThroughMessageId;

    /** 摘要最近一次成功更新时间。 */
    private LocalDateTime summaryUpdatedAt;

    /** 摘要并发更新版本。 */
    @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long summaryVersion = 0L;

    /** 摘要来源消息的稳定哈希。 */
    private String summarySourceHash;

    /** 会话关联的消息 */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MessageEntity> messages = new ArrayList<>();

    public void addMessage(MessageEntity message) {
        messages.add(message);
        message.setSession(this);
    }
}
