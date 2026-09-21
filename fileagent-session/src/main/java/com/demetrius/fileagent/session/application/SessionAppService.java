package com.demetrius.fileagent.session.application;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.RenameSessionReq;
import com.demetrius.fileagent.api.dto.SessionDto;

import java.util.List;

/**
 * 会话应用服务（用例契约）。
 * 由协作者提供 {@code SessionAppServiceImpl} 实现（M1）。
 */
public interface SessionAppService {

    SessionDto createSession(CreateSessionReq req);

    List<SessionDto> listSessions();

    List<MessageDto> listMessages(Long sessionId);

    /**
     * 重命名会话（标题去空白后落库，空白标题拒绝）。
     *
     * @param id  会话 id
     * @param req 新标题
     * @return 更新后的会话信息
     */
    SessionDto renameSession(Long id, RenameSessionReq req);

    /**
     * 删除会话：消息随会话级联删除（JPA cascade）。
     *
     * @param id 会话 id
     */
    void deleteSession(Long id);
}
