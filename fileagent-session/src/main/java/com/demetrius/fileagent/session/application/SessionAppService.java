package com.demetrius.fileagent.session.application;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
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
}
