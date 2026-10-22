package com.demetrius.fileagent.action.interfaces;

import com.demetrius.fileagent.action.application.MockExpenseDraftService;
import com.demetrius.fileagent.action.infrastructure.persistence.MockExpenseDraftJpaRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mock HTTP 接口认证和幂等响应测试，使用真实 JPA 存储。
 *
 * @author raosaijie
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MockExpenseDraftControllerTest {

    private static final String URL = "/internal/action-mock/v1/expense-drafts";
    private static final String BODY = "{\"amount\":100,\"memo\":\"test\",\"bankAccount\":\"secret-account\"}";

    @Autowired
    private MockExpenseDraftJpaRepository repository;

    @TempDir
    private Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        var provider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        var service = new MockExpenseDraftService(repository,
                new EnvelopeCrypto(new AesGcmCipher(provider), provider), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new MockExpenseDraftController(service, "demo-token")).build();
    }

    @Test
    void shouldRejectMissingOrWrongBearerWithoutCreatingAnything() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Idempotency-Key", "key-1")).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer wrong").header("Idempotency-Key", "key-1"))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldCreateReplayAndRejectConflictingRequestsWithoutExposingAccount() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer demo-token").header("Idempotency-Key", "key-2"))
                .andExpect(status().isCreated()).andExpect(header().exists("X-Request-Id"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-account"))));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer demo-token").header("Idempotency-Key", "key-2"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"idempotentReplay\":true")));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY.replace("100", "200"))
                .header("Authorization", "Bearer demo-token").header("Idempotency-Key", "key-2"))
                .andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectEmptyConfiguredToken() {
        assertThatThrownBy(() -> new MockExpenseDraftController(null, " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldReadPersistedDraftOnlyWithBearerToken() throws Exception {
        String response = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer demo-token").header("Idempotency-Key", "key-read"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String draftId = new ObjectMapper().readTree(response).get("data").get("draftId").asString();
        mvc.perform(get(URL + "/" + draftId)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/" + draftId).header("Authorization", "Bearer demo-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(draftId)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-account"))));
    }
}
