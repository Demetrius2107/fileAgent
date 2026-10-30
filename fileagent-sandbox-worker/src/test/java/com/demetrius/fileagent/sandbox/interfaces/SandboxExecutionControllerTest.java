package com.demetrius.fileagent.sandbox.interfaces;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.sandbox.application.SandboxExecutionService;
import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import com.demetrius.fileagent.sandbox.infrastructure.security.SandboxWorkerAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sandbox Worker 内部接口的认证和输入边界测试，确保调用方无法下发容器运行参数。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@ExtendWith(MockitoExtension.class)
class SandboxExecutionControllerTest {

    private static final String TOKEN = "sandbox-test-token";
    private static final String EXECUTION_ID = "bf8c3fa4-7923-41d4-a64d-8c5edb421f6b";
    private static final String IMAGE_ID = "sha256:68c6b1ba2df9d414d9baeb2d6234e82ef68c41eb7eb20463363d6bcddedaaa37";

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken(TOKEN);
        properties.setBindAddress("127.0.0.1");
        properties.setImageId(IMAGE_ID);
        SandboxWorkerAuthentication authentication = new SandboxWorkerAuthentication(properties);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SandboxExecutionController(sandboxExecutionService, authentication, objectMapper))
                .setControllerAdvice(new SandboxWorkerExceptionHandler())
                .addFilters(new SandboxExecutionRequestFilter(authentication, objectMapper))
                .build();
    }

    @Test
    void shouldRejectRequestWithoutValidBearerToken() throws Exception {
        mockMvc.perform(post("/internal/sandbox/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(sandboxExecutionService, never()).execute(any());
    }

    @Test
    void shouldRejectOversizedRawBodyBeforeJacksonOrServiceExecution() throws Exception {
        String oversized = "{\"code\":\"" + "x".repeat(SandboxExecutionRequestFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        verify(sandboxExecutionService, never()).execute(any());
    }

    @Test
    void shouldAuthenticateBeforeReadingAnOversizedRequestBody() throws Exception {
        String oversized = "{\"code\":\"" + "x".repeat(SandboxExecutionRequestFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/internal/sandbox/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(sandboxExecutionService, never()).execute(any());
    }

    @Test
    void shouldAcceptOnlyExecutionIdCodeAndInputJson() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", EXECUTION_ID);
        body.put("code", "print(input_data['x'])");
        body.put("inputJson", "{\"x\":3}");
        body.put("image", "attacker/image");
        body.put("command", "sh -c whoami");
        body.put("mount", "/host:/host");
        body.put("network", "host");
        body.put("user", "root");
        body.put("capabilities", "SYS_ADMIN");
        body.put("resources", "unlimited");
        body.put("runtimeFlags", "--privileged");

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(sandboxExecutionService, never()).execute(any());
    }

    @Test
    void shouldRejectInvalidExecutionIdAndOversizedPayloads() throws Exception {
        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("not-a-uuid", "print(1)", "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(EXECUTION_ID, "x".repeat(SandboxExecutionController.MAX_CODE_BYTES + 1), "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(EXECUTION_ID, "print(1)",
                                "x".repeat(SandboxExecutionController.MAX_INPUT_BYTES + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(EXECUTION_ID, "print(1)", "not-json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(EXECUTION_ID, "print(1)", "[1,2,3]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(sandboxExecutionService, never()).execute(any());
    }

    @Test
    void shouldForwardValidatedRequestAndReturnControlledResponse() throws Exception {
        when(sandboxExecutionService.execute(any())).thenReturn(
                new SandboxExecutionResponse("SUCCEEDED", "3\n", "", null));

        mockMvc.perform(post("/internal/sandbox/executions")
                        .header(SandboxExecutionController.AUTHORIZATION_HEADER, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.stdout").value("3\n"));

        verify(sandboxExecutionService).execute(new SandboxExecutionRequest(
                EXECUTION_ID, "print(input_data['x'])", "{\"x\":3}"));
    }

    @Test
    void shouldRejectBlankTokenAndNonLoopbackAddressAtStartup() {
        SandboxWorkerProperties blankToken = new SandboxWorkerProperties();
        blankToken.setToken(" ");
        blankToken.setBindAddress("127.0.0.1");
        assertThatThrownBy(blankToken::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILEAGENT_SANDBOX_TOKEN");

        SandboxWorkerProperties remoteAddress = new SandboxWorkerProperties();
        remoteAddress.setToken(TOKEN);
        remoteAddress.setBindAddress("0.0.0.0");
        assertThatThrownBy(remoteAddress::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");

        SandboxWorkerProperties mutableImage = new SandboxWorkerProperties();
        mutableImage.setToken(TOKEN);
        mutableImage.setBindAddress("127.0.0.1");
        mutableImage.setImageId("localhost/fileagent-python-sandbox:phase3d-v1");
        assertThatThrownBy(mutableImage::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imageId");
    }

    private String validRequest() throws Exception {
        return request(EXECUTION_ID, "print(input_data['x'])", "{\"x\":3}");
    }

    private String request(String executionId, String code, String inputJson) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "executionId", executionId,
                "code", code,
                "inputJson", inputJson));
    }
}
