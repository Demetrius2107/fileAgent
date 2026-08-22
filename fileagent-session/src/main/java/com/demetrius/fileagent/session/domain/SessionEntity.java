package com.demetrius.fileagent.session.domain;

import com.demetrius.fileagent.model.enums.ActionType;
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

    /** 会话关联的消息 */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MessageEntity> messages = new ArrayList<>();

    public void addMessage(MessageEntity message) {
        messages.add(message);
        message.setSession(this);
    }
}
