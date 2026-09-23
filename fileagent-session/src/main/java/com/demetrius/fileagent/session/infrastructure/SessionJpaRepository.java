package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.session.domain.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话 JPA 仓储（Spring Data 自动实现）。
 * 领域契约见 {@link com.demetrius.fileagent.session.domain.SessionRepository}。
 */
public interface SessionJpaRepository extends JpaRepository<SessionEntity, Long> {

    /** 全部会话，按更新时间倒序（最近活跃在前） */
    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SessionEntity s
               set s.summary = :summary,
                   s.summaryThroughMessageId = :throughMessageId,
                   s.summaryUpdatedAt = :updatedAt,
                   s.summaryVersion = s.summaryVersion + 1,
                   s.summarySourceHash = :sourceHash
             where s.id = :sessionId
               and s.summaryVersion = :expectedVersion
               and (s.summaryThroughMessageId is null
                    or s.summaryThroughMessageId < :throughMessageId)
            """)
    int updateSummary(
            @Param("sessionId") Long sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("summary") String summary,
            @Param("throughMessageId") Long throughMessageId,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("sourceHash") String sourceHash);
}
