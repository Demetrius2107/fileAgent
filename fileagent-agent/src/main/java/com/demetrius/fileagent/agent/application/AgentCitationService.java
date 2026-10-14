package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.api.dto.AgentCitation;
import com.demetrius.fileagent.api.enums.CitationVerificationStatus;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort.KnowledgeChunkContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2C 结构化引用服务：把回答中的来源标记映射为实际进入上下文的证据，
 * 并在回答后核验每条引用的证据支持关系。
 * <p>
 * 核验只读索引做包含性比对，不改写回答；验证失败仅标注状态（如 QUOTE_MISMATCH），
 * 不阻断回答交付。最终引用必须来自 {@link AgentRun#contextEvidences()}，
 * 声明了从未进入上下文的来源记为 NOT_IN_CONTEXT（伪造/缺失引用）。
 */
@Component
@RequiredArgsConstructor
public class AgentCitationService {

    /** 与系统提示词约定的来源标记格式保持一致。 */
    private static final Pattern CITATION = Pattern.compile("\\[来源：([^\\]]+)\\]");
    /** 单个来源文件最多映射的 chunk 证据数，防止大文件引用刷屏。 */
    private static final int MAX_CHUNKS_PER_FILE = 3;
    /** 引用原文摘录长度（规范化后），同时用于 quoteHash 与包含性核验。 */
    private static final int QUOTE_CODE_POINTS = 200;

    private final KnowledgeContextPort knowledgeContextPort;

    /** 从回答文本抽取声明的来源文件名（保持出现顺序，代码块与行内代码不参与匹配）。 */
    public List<String> extractSourceFilenames(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> files = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(visibleMarkdownText(answer));
        while (matcher.find()) {
            for (String source : matcher.group(1).split("[；;]")) {
                String name = source.trim();
                if (!name.isBlank()) {
                    files.add(name);
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * 引用映射 + 回答后核验：逐个声明的来源映射到上下文证据（单文件最多 3 个 chunk），
     * 再批量重读索引核对原文仍然命中。核验基础设施异常时按无法核验处理，不伪造核验通过。
     */
    public List<AgentCitation> extractAndVerify(AgentRun run, String answer) {
        List<String> citedFiles = extractSourceFilenames(answer);
        if (citedFiles.isEmpty()) {
            return List.of();
        }
        List<AgentRun.ContextEvidence> evidences = run.contextEvidences();
        Map<String, List<AgentRun.ContextEvidence>> byFile = new LinkedHashMap<>();
        for (AgentRun.ContextEvidence evidence : evidences) {
            if (evidence.filename() != null) {
                byFile.computeIfAbsent(evidence.filename(), ignored -> new ArrayList<>())
                        .add(evidence);
            }
        }
        List<AgentCitation> citations = new ArrayList<>();
        Map<String, String> currentContentById = readCurrentContent(evidences);
        for (String filename : citedFiles) {
            List<AgentRun.ContextEvidence> matches = byFile.get(filename);
            if (matches == null || matches.isEmpty()) {
                citations.add(AgentCitation.notInContext(filename));
                continue;
            }
            for (AgentRun.ContextEvidence evidence : matches.stream().limit(MAX_CHUNKS_PER_FILE).toList()) {
                citations.add(toCitation(evidence, currentContentById.get(evidence.chunkId())));
            }
        }
        return List.copyOf(citations);
    }

    private Map<String, String> readCurrentContent(List<AgentRun.ContextEvidence> evidences) {
        List<String> chunkIds = evidences.stream()
                .map(AgentRun.ContextEvidence::chunkId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<String, String> contentById = new LinkedHashMap<>();
        if (chunkIds.isEmpty()) {
            return contentById;
        }
        try {
            for (KnowledgeChunkContext chunk : knowledgeContextPort.read(chunkIds)) {
                contentById.put(chunk.chunkId(), chunk.content());
            }
        } catch (RuntimeException exception) {
            // 核验读取失败时内容表保持为空，所有引用按 CHUNK_MISSING 处理，不阻断收尾
            return contentById;
        }
        return contentById;
    }

    private AgentCitation toCitation(AgentRun.ContextEvidence evidence, String currentContent) {
        String quote = quote(evidence.content());
        CitationVerificationStatus status;
        if (currentContent == null) {
            status = CitationVerificationStatus.CHUNK_MISSING;
        } else if (quote == null || normalize(currentContent).contains(quote)) {
            status = CitationVerificationStatus.SUPPORTED;
        } else {
            status = CitationVerificationStatus.QUOTE_MISMATCH;
        }
        return new AgentCitation(
                evidence.fileId(),
                evidence.documentVersion(),
                evidence.chunkId(),
                evidence.filename(),
                evidence.page(),
                evidence.sheetName(),
                evidence.sectionId(),
                evidence.chunkIndex(),
                quote,
                quote == null ? null : sha256Hex(quote),
                status);
    }

    /** 规范化摘录：压缩空白并截断到固定长度，保证 quoteHash 稳定可比。 */
    private String quote(String content) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return null;
        }
        int end = normalized.offsetByCodePoints(0,
                Math.min(normalized.codePointCount(0, normalized.length()), QUOTE_CODE_POINTS));
        return normalized.substring(0, end);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String visibleMarkdownText(String text) {
        StringBuilder visible = new StringBuilder(text.length());
        boolean inFence = false;
        boolean inInlineCode = false;
        for (int index = 0; index < text.length(); index++) {
            if (text.startsWith("```", index)) {
                inFence = !inFence;
                index += 2;
                continue;
            }
            if (!inFence && text.charAt(index) == '`') {
                inInlineCode = !inInlineCode;
                continue;
            }
            if (!inFence && !inInlineCode) {
                visible.append(text.charAt(index));
            }
        }
        return visible.toString();
    }
}
