import os
import io
import json
import fitz
import psycopg2
import requests
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from sentence_transformers import SentenceTransformer, CrossEncoder
from dotenv import load_dotenv

load_dotenv()

app = FastAPI(title="AI RAG Service")

# --- MIDDLEWARE & LOGGING ---
@app.middleware("http")
async def log_requests(request, call_next):
    print(f"🌍 INCOMING: {request.method} {request.url.path}")
    response = await call_next(request)
    print(f"✅ OUTGOING: {request.method} {request.url.path} -> {response.status_code}")
    return response

# --- ENVIRONMENT CONFIG ---
DB_URL = os.getenv("DB_URL")
OLLAMA_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", 700))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", 100))
SEARCH_LIMIT = int(os.getenv("VECTOR_SEARCH_LIMIT", 8))
RERANK_TOP_K = int(os.getenv("RERANK_TOP_K", 2))

# --- ML MODELS ---
print("Loading BGE-Large-v1.5 Embedding Model...")
embedding_model = SentenceTransformer('BAAI/bge-large-en-v1.5')
embedding_model.max_seq_length = 512

print("Loading Cross-Encoder Reranker...")
reranker_model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')

# --- DB HELPERS ---
def get_db_conn():
    try:
        return psycopg2.connect(DB_URL)
    except Exception as e:
        print(f"FAILED TO CONNECT TO DB: {e}")
        raise e

# --- MODELS ---
class QueryRequest(BaseModel):
    question: str
    lecture_id: Optional[str] = None

class QueryResult(BaseModel):
    answer: str
    chunks: List[str]

class RetrieveContextRequest(BaseModel):
    question: str
    lecture_id: str

# --- CHUNKING ---
def chunk_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP):
    chunks = []
    start = 0
    while start < len(text):
        end = start + size
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start += (size - overlap)
    return chunks

# 🚀 OPTIMIZATION FUNCTION
def trim_chunks(chunks, max_chars=800):
    trimmed = []
    for chunk in chunks:
        if len(chunk) > max_chars:
            trimmed.append(chunk[:max_chars])
        else:
            trimmed.append(chunk)
    return trimmed

# --- ADD DOCUMENT ---
@app.post("/add")
async def add_document(lecture_id: str = Form(...), file: UploadFile = File(...)):
    try:
        content = await file.read()
        pdf_doc = fitz.open(stream=io.BytesIO(content), filetype="pdf")

        full_text = ""
        for page in pdf_doc:
            full_text += page.get_text()

        chunks = chunk_text(full_text)
        print(f"Indexing {len(chunks)} chunks...")

        embeddings = embedding_model.encode(
            chunks,
            batch_size=32,
            show_progress_bar=False,
            normalize_embeddings=True
        )

        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM documents WHERE metadata->>'lecture_id' = %s",
                    (lecture_id,)
                )

                for chunk, embedding in zip(chunks, embeddings):
                    cur.execute(
                        "INSERT INTO documents (content, embedding, metadata) VALUES (%s, %s::vector, %s)",
                        (chunk, embedding.tolist(), json.dumps({"lecture_id": lecture_id}))
                    )
                conn.commit()

        return {"message": "Indexed", "chunks": len(chunks)}

    except Exception as e:
        print(f"Error in /add: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# --- QUERY ---
# --- QUERY ---
@app.post("/query", response_model=QueryResult)
async def query_rag(request: QueryRequest):
    try:
        query_emb = embedding_model.encode(
            request.question,
            normalize_embeddings=True
        )

        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT content FROM documents WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), SEARCH_LIMIT)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]

        if not retrieved_chunks:
            return QueryResult(answer="No content found", chunks=[])

        pairs = [[request.question, chunk] for chunk in retrieved_chunks]

        scores = reranker_model.predict(
            pairs,
            batch_size=16,
            show_progress_bar=False
        )

        ranked_chunks = [
            chunk for _, chunk in sorted(
                zip(scores, retrieved_chunks),
                key=lambda x: x[0],
                reverse=True
            )
        ]

        top_chunks = ranked_chunks[:RERANK_TOP_K]

        # 🚀 KEY OPTIMIZATION
        top_chunks = trim_chunks(top_chunks)

        context = "\n\n---\n\n".join(top_chunks)

        prompt = f"""
You are an expert teaching assistant.

Use ONLY the context.

CONTEXT:
{context}

QUESTION:
{request.question}

ANSWER:
"""

        # ❌ OLD CODE - REMOVE THIS
        ollama_response = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={
                "model": "phi3:latest",
                "prompt": prompt,
                "stream": False
            }
        )

        answer = ollama_response.json().get("response", "")
        # ❌ END OLD CODE

        return QueryResult(answer=answer, chunks=top_chunks)

    except Exception as e:
        print(f"Error in /query: {e}")
        raise HTTPException(status_code=500, detail=str(e))
# --- RETRIEVE CONTEXT ---
@app.post("/retrieve-context")
async def retrieve_context(request: RetrieveContextRequest):
    try:
        query_emb = embedding_model.encode(
            request.question,
            normalize_embeddings=True
        )

        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT content FROM documents WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), SEARCH_LIMIT)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]

        if not retrieved_chunks:
            return {"chunks": []}

        pairs = [[request.question, chunk] for chunk in retrieved_chunks]

        scores = reranker_model.predict(
            pairs,
            batch_size=16,
            show_progress_bar=False
        )

        ranked = [
            chunk for _, chunk in sorted(
                zip(scores, retrieved_chunks),
                key=lambda x: x[0],
                reverse=True
            )
        ]

        top_chunks = ranked[:RERANK_TOP_K]

        # 🚀 OPTIMIZATION
        top_chunks = trim_chunks(top_chunks)

        return {"chunks": top_chunks}

    except Exception as e:
        print(f"Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# --- DEBUG ---
@app.get("/debug/{lecture_id}")
async def debug(lecture_id: str):
    with get_db_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM documents WHERE metadata->>'lecture_id' = %s",
                (lecture_id,)
            )
            count = cur.fetchone()[0]
    return {"chunks": count}

# --- RUN ---
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)