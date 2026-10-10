package zelisline.ub.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.domain.AiKnowledgeChunk;
import zelisline.ub.ai.domain.AiKnowledgeDocument;
import zelisline.ub.ai.repository.AiKnowledgeChunkRepository;
import zelisline.ub.ai.repository.AiKnowledgeDocumentRepository;

/**
 * Per-business knowledge base: stores documents, chunks them, and retrieves the most relevant
 * chunks for a query by lexical overlap (dialect-agnostic — no FULLTEXT/pgvector dependency).
 *
 * <p>Retrieved chunks ground the AI reply / auto-reply prompt. See
 * {@code docs/scopes/whatsapp-crm/SCOPE.md} §M5 (knowledge base).
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final int CHUNK_CHARS = 700;
    private static final int MIN_TOKEN = 3;

    private final AiKnowledgeDocumentRepository documentRepository;
    private final AiKnowledgeChunkRepository chunkRepository;

    public record DocumentRow(String id, String title, String content, Instant createdAt) {
    }

    @Transactional
    public DocumentRow createDocument(String businessId, String title, String content) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setBusinessId(businessId);
        document.setTitle(title.trim());
        document.setContent(content);
        AiKnowledgeDocument saved = documentRepository.save(document);
        reindex(businessId, saved, content);
        return toRow(saved);
    }

    @Transactional
    public DocumentRow updateDocument(String businessId, String id, String title, String content) {
        AiKnowledgeDocument document = documentRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        document.setTitle(title.trim());
        document.setContent(content);
        document.setUpdatedAt(Instant.now());
        AiKnowledgeDocument saved = documentRepository.save(document);
        reindex(businessId, saved, content);
        return toRow(saved);
    }

    private void reindex(String businessId, AiKnowledgeDocument document, String content) {
        chunkRepository.deleteByDocumentId(document.getId());
        int ordinal = 0;
        for (String piece : chunkText(content)) {
            AiKnowledgeChunk chunk = new AiKnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setBusinessId(businessId);
            chunk.setOrdinal(ordinal++);
            chunk.setContent(piece);
            chunkRepository.save(chunk);
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentRow> listDocuments(String businessId) {
        return documentRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(KnowledgeBaseService::toRow)
                .toList();
    }

    @Transactional
    public void deleteDocument(String businessId, String id) {
        AiKnowledgeDocument document = documentRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        chunkRepository.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
    }

    /** Top-{@code limit} chunks whose text shares tokens with the query (empty when none match). */
    @Transactional(readOnly = true)
    public List<String> retrieve(String businessId, String query, int limit) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findByBusinessId(businessId).stream()
                .map(chunk -> Map.entry(chunk.getContent(), score(tokens, chunk.getContent())))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, limit))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static DocumentRow toRow(AiKnowledgeDocument document) {
        return new DocumentRow(document.getId(), document.getTitle(), document.getContent(), document.getCreatedAt());
    }

    static List<String> chunkText(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        String normalized = content.replace("\r\n", "\n").trim();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + CHUNK_CHARS);
            if (end < normalized.length()) {
                int lastSpace = normalized.lastIndexOf(' ', end);
                if (lastSpace > start + CHUNK_CHARS / 2) {
                    end = lastSpace;
                }
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return out;
    }

    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= MIN_TOKEN) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    static int score(Set<String> tokens, String text) {
        if (text == null) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (lower.contains(token)) {
                hits++;
            }
        }
        return hits;
    }
}
