package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.api.dto.ContentLocation;
import com.demetrius.fileagent.api.dto.DocumentAssetPage;
import com.demetrius.fileagent.api.dto.DocumentAssetSummary;
import com.demetrius.fileagent.api.enums.DocumentAssetType;
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

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RagFileController} 页面资产接口测试。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class RagFileAssetControllerTest {

    @Mock
    private RagFileAppService ragFileAppService;

    @InjectMocks
    private RagFileController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listAssetsShouldReturnCursorPageWithoutStorageKey() throws Exception {
        DocumentAssetSummary asset = new DocumentAssetSummary(
                "asset-1", 3, DocumentAssetType.PAGE, 21, ContentLocation.pdfPage(21),
                "image/png", 1200, 1600, 2048L);
        when(ragFileAppService.listAssets(7L, 3, 20, 20))
                .thenReturn(new DocumentAssetPage(List.of(asset), 21));

        mockMvc.perform(get("/api/rag-files/7/assets")
                        .param("documentVersion", "3")
                        .param("afterAssetIndex", "20")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].assetId").value("asset-1"))
                .andExpect(jsonPath("$.data.items[0].documentVersion").value(3))
                .andExpect(jsonPath("$.data.items[0].location.page").value(21))
                .andExpect(jsonPath("$.data.nextAssetIndex").value(21))
                .andExpect(jsonPath("$.data.items[0].storageKey").doesNotExist());

        verify(ragFileAppService).listAssets(7L, 3, 20, 20);
    }

    @Test
    void listAssetsShouldRejectOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/rag-files/7/assets")
                        .param("documentVersion", "1")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void assetContentShouldStreamFixedImageHeaders() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        when(ragFileAppService.loadAsset(7L, 3, "asset-1"))
                .thenReturn(new RagFileAppService.DocumentAssetContent(
                        "image/png", png.length, new ByteArrayInputStream(png)));

        mockMvc.perform(get("/api/rag-files/7/assets/asset-1/content")
                        .param("documentVersion", "3"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Length", String.valueOf(png.length)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png));
    }

    @Test
    void assetContentShouldReturn404ForMismatchedOwnership() throws Exception {
        when(ragFileAppService.loadAsset(8L, 3, "asset-1"))
                .thenThrow(new BizException(404, "文档资产不存在"));

        mockMvc.perform(get("/api/rag-files/8/assets/asset-1/content")
                        .param("documentVersion", "3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
