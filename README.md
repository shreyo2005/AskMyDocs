# AskMyDocs

Upload a PDF, ask questions about it, get answers grounded in the actual text.
A retrieval-augmented generation (RAG) pipeline built on Spring Boot.

Runs entirely locally — no API keys, nothing leaves your machine.

---

## Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 4.1 · Java 17 |
| AI | Spring AI · Ollama (`llama3.2` + `nomic-embed-text`) |
| Vector store | PostgreSQL 16 + pgvector (768 dimensions, HNSW, cosine) |
| Frontend | Vanilla HTML/CSS/JS served from `/static` |

---

## Running it

**Prerequisites:** Java 17, Docker, and [Ollama](https://ollama.com).

```bash
# 1. Pull the models
ollama pull llama3.2
ollama pull nomic-embed-text

# 2. Start Postgres with pgvector
docker compose up -d

# 3. Run
./mvnw spring-boot:run
```

Open **http://localhost:8080**.

The frontend is served by Spring itself, so there's no separate dev server and
no CORS configuration to deal with.

---

## How it works



## for reference  "Multi-hop questions requiring synthesis across chunks can produce plausible but incorrect answers
becuase of which switching to RetrievalAugmentedGeneration is necessary 
```
PDF upload
   ↓  PagePdfDocumentReader          one Document per page
   ↓  TokenTextSplitter              overlapping chunks, token-counted
   ↓  metadata tagging               documentId + filename per chunk
   ↓  nomic-embed-text               768-dim vector per chunk
   ↓  pgvector                       stored with HNSW index

Question
   ↓  embed the question
   ↓  cosine similarity search       top 6 chunks, optionally filtered by document
   ↓  build prompt with retrieved context
   ↓  llama3.2                       answer constrained to that context
```

A 52-page lecture PDF produces roughly 275,000 characters and 178 chunks —
which is the clearest argument for chunking I've come across. Embedding one
vector for a whole document averages everything into something that means
nothing specific and matches nothing well.

---

## API

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/documents/upload` | Multipart PDF upload; extracts, chunks, embeds, stores |
| `GET` | `/api/documents/ask?question=&documentId=` | RAG answer with source excerpts; `documentId` optional |
| `GET` | `/api/documents/summarize?documentId=` | Map-reduce summary of a document |
| `GET` | `/api/documents` | List indexed documents |

Summarisation deliberately does **not** use similarity search. "Summarise this
document" has no specific retrieval target, so it would return arbitrary
passages. Instead each chunk is summarised, then the summaries are summarised.

---

## Design notes

**Retrieval is written by hand.** Spring AI ships `QuestionAnswerAdvisor` and
`RetrievalAugmentationAdvisor` which handle retrieve-then-augment for you. I
built the loop manually first — similarity search, join the chunks, construct
the prompt — to understand what those advisors actually do before delegating
to them. Swapping them in is the next change.

**Every chunk carries metadata.** `documentId` and `filename` are attached at
ingest time, which is what makes source citation and per-document filtering
possible. Retrofitting this later would mean re-embedding everything.

**Scanned PDFs are detected, not silently stored.** If text extraction yields
zero characters the upload is rejected with an explanation rather than
indexing an empty document.

---

## Known limitations

- **Embedding is slow.** One Ollama round trip per chunk, sequentially. A
  52-page PDF takes a minute or two on CPU. Batching or a hosted embedding
  provider would fix this.
- **The document list is in memory.** It's a `Map` on the controller, so it
  resets on restart. The chunks survive in Postgres; only the filename listing
  is lost. Needs a small `documents` table.
- **No authentication.** Anyone with access to the port can upload and query.
- **No delete.** Chunks can only be removed with SQL directly.
- **Summarisation is capped at 12 chunks** because each one is a separate LLM
  call on a local model.

---

## Next

- Swap in `RetrievalAugmentationAdvisor` with query transformation and re-ranking
- Persist the document list
- Batch embedding calls to cut upload latency
- A profile for hosted embeddings alongside the local Ollama one