package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.exception.GlobalExceptionHandler;
import com.demetrius.fileagent.document.application.RagFileAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RagFileController} 删除接口的 MockMvc 测试：HTTP 状态与 ApiResult 结构。
 *
 * @author Demetrius
 */
@ExtendWith(MockitoExtension.class)
class RagFileControllerTest {

    @Mock
    private RagFileAppService ragFileAppService;

    @InjectMocks
    private RagFileController ragFileController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ragFileController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void deleteShouldReturnApiResultOkWhenServiceSucceeds() throws Exception {
        mockMvc.perform(delete("/api/rag-files/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data").value(true));

        verify(ragFileAppService).deleteRagFile(7L);
    }

    @Test
    void deleteShouldMapBizErrorTo400WithMessage() throws Exception {
        doThrow(new BizException("知识库文件不存在: 9")).when(ragFileAppService).deleteRagFile(9L);

        mockMvc.perform(delete("/api/rag-files/9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("知识库文件不存在: 9"));

        verify(ragFileAppService).deleteRagFile(9L);
    }
}
