# 🎓 LearnAI — Backend

> Spring Boot backend for the **LearnAI AI Teaching Assistant** — a platform that turns lecture PDFs into structured summaries, RAG-powered Q&A, and auto-generated quizzes.

---

## 📐 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      React Frontend                         │
│          (SockJS + STOMP WebSocket, REST API)               │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│               Spring Boot Backend (Port 8080)               │
│                                                             │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ AuthController│  │LectureControl│  │  QuizController   │  │
│  │  JWT Auth   │  │  Upload/Hist │  │  Generate/Submit  │  │
│  └─────────────┘  └──────────────┘  └───────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              WebSocket / STOMP Layer                  │   │
│  │    /topic/lectures/{id}   /topic/qa/{id}             │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │  LlmClient  │  │ PythonRagCli │  │  StreamingServices│  │
│  │  OpenAI     │  │  BGE Embeds  │  │  Map-Reduce Summ  │  │
│  │  Claude     │  │  pgvector    │  │  Streaming Q&A    │  │
│  │  Gemini     │  │  Reranking   │  └───────────────────┘  │
│  │  Ollama     │  └──────────────┘                         │
│  └─────────────┘                                           │
└──────────────────────────┬──────────────────────────────────┘
                           │
          ┌────────────────┴────────────────┐
          │                                 │
┌─────────▼────────┐             ┌──────────▼───────┐
│  PostgreSQL/Neon │             │  Python RAG Svc  │
│  (pgvector)      │             │  (Port 8001)     │
│  lectures table  │             │  FastAPI         │
│  users table     │             │  BGE Embeddings  │
│  quiz_attempts   │             │  Neon pgvector   │
│  token_blacklist │             │  FAQ Cache       │
└──────────────────┘             └──────────────────┘
```

---

## ✨ Features

| Feature | Description |
|---|---|
| **JWT Auth** | Access (15 min) + Refresh (7 day) tokens with blacklist on logout |
| **Multi-LLM Support** | Plug-and-play: OpenAI GPT-4, Anthropic Claude, Google Gemini, Ollama (local) |
| **PDF Processing** | Apache PDFBox extraction with header/footer removal and page number stripping |
| **Content Caching** | MD5 hash-based deduplication — same PDF never hits the LLM twice |
| **Streaming Summaries** | Map-Reduce pipeline for long docs, streamed token-by-token via WebSocket |
| **RAG Q&A** | Python microservice handles BGE embeddings → pgvector → reranking → Ollama |
| **FAQ Cache** | Frequently asked questions are cached in the RAG service for instant answers |
| **Quiz Generation** | MCQ quiz generated from RAG context using any configured LLM |
| **WebSocket / STOMP** | Real-time streaming via SockJS with SimpMessagingTemplate |
| **Stream Cancellation** | Users can stop generation mid-stream |
| **User Statistics** | Total lectures, pages processed, quiz attempts, average scores, study days |

---

## 🛠 Tech Stack

- **Java 17** + **Spring Boot 3.x**
- **Spring Security** (JWT stateless auth)
- **Spring WebSocket** (STOMP + SockJS)
- **Spring Data JPA** + **PostgreSQL** (Neon.tech cloud)
- **Apache PDFBox** (PDF text extraction)
- **WebClient / Reactor** (non-blocking streaming to Ollama)
- **Docker** (multi-stage build, deployed on Render)

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+
- PostgreSQL instance (or [Neon.tech](https://neon.tech) free tier)
- At least one LLM configured (see below)
- *(Optional)* Python RAG service running on port 8001

### 1. Clone & Configure

```bash
git clone https://github.com/your-username/learnai-backend.git
cd learnai-backend
```

Copy the example environment config:

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

### 2. Set Environment Variables

The app reads these environment variables (or falls back to `application.properties` defaults):

```bash
# Database
DB_URL=jdbc:postgresql://your-host/neondb?sslmode=require
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password

# JWT (use a strong random 256-bit secret in production)
JWT_SECRET=your_base64_encoded_secret_here

# LLM Provider: openai | claude | gemini | ollama
LLM_PROVIDER=ollama

# API Keys (only needed for the chosen provider)
OPENAI_API_KEY=sk-...
CLAUDE_API_KEY=sk-ant-...
GEMINI_API_KEY=AIza...

# CORS — comma-separated frontend origins
CORS_ALLOWED_ORIGINS=http://localhost:5173,https://your-frontend.vercel.app

# Python RAG service URL (optional, defaults to localhost:8001)
PYTHON_RAG_URL=http://localhost:8001
```

### 3. Build & Run

```bash
# Development
mvn spring-boot:run

# Production JAR
mvn package -DskipTests -B
java -jar target/teaching-assistant-1.0.0.jar
```

The server starts on **port 8080** (or `$PORT` in production).

---

## 🔑 LLM Provider Configuration

Switch providers by setting `LLM_PROVIDER`. Only the configured provider's API key is needed.

| `LLM_PROVIDER` | Model | Cost | Notes |
|---|---|---|---|
| `openai` | `gpt-4` | Paid | Best quality |
| `claude` | `claude-sonnet-4-6-20250514` | Paid | Strong reasoning |
| `gemini` | `gemini-pro` | Free tier | Good quality |
| `ollama` | `llama3.2:latest` | Free | Runs locally, no API key |

For **Ollama** (default for local dev):
```bash
# Install Ollama: https://ollama.ai
ollama pull llama3.2
ollama serve  # runs on port 11434
```

---

## 📡 API Reference

### Authentication

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/signup` | Register a new account |
| `POST` | `/api/auth/login` | Login and receive JWT tokens |
| `POST` | `/api/auth/refresh` | Exchange refresh token for new access token |

### Lectures

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/lecture/process?mode={mode}` | Smart upload (summary/chat/quiz mode) |
| `POST` | `/api/lecture/summarize` | Full sync summarization (legacy) |
| `POST` | `/api/lecture/index` | Quick index without summary |
| `GET` | `/api/lecture/history` | List all lectures for current user |
| `GET` | `/api/lecture/{id}` | Get a single lecture with summary |
| `DELETE` | `/api/lecture/{id}` | Delete a lecture |
| `POST` | `/api/lecture/{id}/reindex` | Re-index PDF into RAG store |
| `POST` | `/api/lecture/{id}/summarize-stream` | Trigger streaming summary (202 + WebSocket) |
| `POST` | `/api/lecture/{id}/stop-stream` | Cancel active streaming session |
| `GET` | `/api/lecture/stats` | User statistics (lectures, quizzes, scores) |
| `GET` | `/api/lecture/health` | Health check with active provider info |

### Q&A

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/lecture/{id}/ask` | Blocking RAG Q&A (legacy) |
| `POST` | `/api/lecture/{id}/ask-stream` | Streaming RAG Q&A (202 + WebSocket) |

### Quizzes

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/quiz/{id}/generate` | Generate MCQ quiz (`?numQuestions=10`) |
| `POST` | `/api/quiz/{id}/submit` | Submit answers and get results |
| `GET` | `/api/quiz/history` | User's quiz attempt history |

### WebSocket Topics

Connect via SockJS at `/ws/lectures`, then subscribe:

| Topic | Event Types | Description |
|---|---|---|
| `/topic/lectures/{id}` | `SUMMARY_CHUNK`, `SUMMARY_COMPLETED`, `SUMMARY_ERROR` | Summary streaming |
| `/topic/qa/{id}` | `ANSWER_CHUNK`, `ANSWER_COMPLETED`, `ANSWER_ERROR` | Q&A streaming |

---

## 🐳 Docker

```bash
# Build
docker build -t learnai-backend .

# Run
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://... \
  -e DB_USERNAME=... \
  -e DB_PASSWORD=... \
  -e JWT_SECRET=... \
  -e LLM_PROVIDER=ollama \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 \
  learnai-backend
```

The Dockerfile uses a two-stage build:
1. **Stage 1** — Maven builds the JAR
2. **Stage 2** — Eclipse Temurin 17 JRE Alpine runs it with `-Xms256m -Xmx400m`

---

## 🗄️ Database Schema

Tables are auto-created by Hibernate (`ddl-auto=update`):

```
users           → id, fullName, email, password (BCrypt), role, createdAt
lectures        → id (UUID), fileName, originalText, summary (JSON), provider,
                  processedAt, fileSizeBytes, pageCount, userId, contentHash, quizData
quiz_attempts   → id, lectureId, lectureFileName, userId, score, totalQuestions,
                  percentage, grade, attemptedAt
token_blacklist → tokenId (jti), email, expiresAt, blacklistedAt
```

---

## 🔄 Processing Modes

### `mode=summary`
Full synchronous pipeline. Returns a complete `SummaryResponse` with structured sections (mainTopic, keyPoints, importantDetails, conclusions, etc.).

### `mode=chat` or `mode=quiz`
Fast async path:
1. Extract PDF text (PDFBox)
2. Index into Python RAG service (BGE embeddings → pgvector)
3. Return `lectureId` immediately — UI can start Q&A right away
4. Frontend connects via WebSocket and triggers `POST /summarize-stream` for live summary

### Map-Reduce for Long Documents
Documents over 8,000 characters are processed in chunks:
- **Map**: Each chunk is summarized individually (streamed section-by-section to the user)
- **Reduce**: All chunk summaries are combined into a final structured analysis (streamed live)

---

## 🔒 Security

- Passwords hashed with **BCrypt**
- JWT tokens signed with HMAC-SHA256
- Token blacklist checked on every request (logout invalidation)
- Access tokens expire in **15 minutes**, refresh tokens in **7 days**
- CORS configured via `CORS_ALLOWED_ORIGINS` environment variable
- Stateless session policy (no server-side sessions)

---

## 🤝 Related Repositories

| Repo | Description |
|---|---|
| **learnai-backend** *(this repo)* | Spring Boot API, WebSocket streaming, LLM integration |
| **learnai-frontend** | React + TypeScript UI with real-time streaming |
| **learnai-rag-service** | Python FastAPI — BGE embeddings, pgvector, reranking, FAQ cache |

---

## 📄 License

MIT — see [LICENSE](LICENSE) for details.
