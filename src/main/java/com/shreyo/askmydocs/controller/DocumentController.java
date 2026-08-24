package com.shreyo.askmydocs.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    /** Filename -> documentId, so the UI can list what has been uploaded. */
    private final Map<String, String> uploaded = new LinkedHashMap<>();

    public DocumentController(VectorStore vectorStore, ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
    }

    // ============================================================
    // UPLOAD
    // ============================================================

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) return error("The file is empty.");

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            return error("Only PDF files are accepted.");
        }

        // 1. Extract - one Document per page
        Resource resource = new ByteArrayResource(file.getBytes());
        List<Document> pages = new PagePdfDocumentReader(resource).get();

        int totalChars = pages.stream().mapToInt(d -> d.getText().length()).sum();
        if (totalChars == 0) {
            return error("No text found in " + name + ". This may be a scanned PDF, which needs OCR.");
        }

        // 2. Chunk
        List<Document> chunks = new TokenTextSplitter().apply(pages);

        // 3. Tag every chunk so we can cite and filter later
        String documentId = UUID.randomUUID().toString();
        for (Document chunk : chunks) {
            chunk.getMetadata().put("documentId", documentId);
            chunk.getMetadata().put("filename", name);
        }

        // 4. Embed and store - one Ollama round trip per chunk, so this is slow
        vectorStore.add(chunks);
        uploaded.put(name, documentId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("filename", name);
        out.put("documentId", documentId);
        out.put("pages", pages.size());
        out.put("chunks", chunks.size());
        out.put("characters", totalChars);
        return out;
    }

    // the endpoint to be able to ask questions

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String question,
                                   @RequestParam(required = false) String documentId) {

        if (question == null || question.isBlank()) {
            return error("Please enter a question.");
        }

        SearchRequest.Builder search = SearchRequest.builder()
                .query(question)
                .topK(6);

        if (documentId != null && !documentId.isBlank()) {
            search.filterExpression("documentId == '" + documentId + "'");
        }

        List<Document> hits = vectorStore.similaritySearch(search.build());

        if (hits == null || hits.isEmpty()) {
            return error("Nothing relevant found. Upload a document first.");
        }

        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = """
                You are answering questions about a document the user uploaded.
                Use ONLY the context below. If the answer is not in the context,
                say so plainly rather than guessing.

                CONTEXT:
                %s

                QUESTION: %s
                """.formatted(context, question);

        String answer = chatClient.prompt(prompt).call().content();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("question", question);
        out.put("answer", answer);
        out.put("sources", sourcesOf(hits));
        return out;
    }

    // ============================================================
    // SUMMARISE
    // ============================================================

    /**
     * Map-reduce summarisation. Similarity search cannot do this - "summarise
     * this document" has no specific target, so retrieval would return
     * arbitrary passages. Instead we summarise chunks, then summarise those.
     *
     * Capped at 12 chunks because each one is a local LLM call.
     */
    @GetMapping("/summarize")
    public Map<String, Object> summarize(@RequestParam String documentId) {

        List<Document> chunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("summary overview main topics")
                        .topK(12)
                        .filterExpression("documentId == '" + documentId + "'")
                        .build());

        if (chunks == null || chunks.isEmpty()) {
            return error("No document found with that id.");
        }

        // MAP - summarise each chunk
        List<String> partials = new ArrayList<>();
        for (Document chunk : chunks) {
            String p = chatClient.prompt(
                    "Summarise the key points of this passage in 2-3 sentences:\n\n"
                            + chunk.getText()).call().content();
            partials.add(p);
        }

        // REDUCE - summarise the summaries
        String combined = String.join("\n\n", partials);
        String summary = chatClient.prompt("""
                Below are summaries of sections from a single document.
                Write one coherent summary of the whole document in 5-8 sentences.
                Do not mention that you were given summaries.

                %s
                """.formatted(combined)).call().content();

        String filename = chunks.get(0).getMetadata().getOrDefault("filename", "document").toString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("filename", filename);
        out.put("sectionsUsed", chunks.size());
        out.put("summary", summary);
        return out;
    }

    // ============================================================
    // LIST
    // ============================================================

    @GetMapping
    public List<Map<String, String>> list() {
        return uploaded.entrySet().stream()
                .map(e -> Map.of("filename", e.getKey(), "documentId", e.getValue()))
                .toList();
    }

    // ============================================================
    // helpers
    // ============================================================

    private List<Map<String, Object>> sourcesOf(List<Document> hits) {
        return hits.stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("filename", d.getMetadata().getOrDefault("filename", "unknown"));
                    m.put("page", d.getMetadata().getOrDefault("page_number", "?"));
                    String text = d.getText();
                    m.put("excerpt", text.length() > 180 ? text.substring(0, 180) + "…" : text);
                    return m;
                })
                .toList();
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }
}