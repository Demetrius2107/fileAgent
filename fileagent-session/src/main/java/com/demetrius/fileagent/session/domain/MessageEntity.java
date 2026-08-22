package com.demetrius.fileagent.session.domain;

import com.demetrius.fileagent.api.enums.MessageType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话消息
 */
@Getter
@Setter
@Entity
@Table(name = "message")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private SessionEntity session;

    /** 消息角色 */
    @Enumerated(EnumType.STRING)
    private MessageType role;

    /** 消息正文（Markdown / 结构化动作 JSON） */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    /** 动作 JSON（assistant 消息专用） */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String actionJson;

    private LocalDateTime createdAt = LocalDateTime.now();
}
