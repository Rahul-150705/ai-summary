import os
os.environ["OMP_NUM_THREADS"] = "1" # Force PyTorch to use 1 thread to avoid memory spikes

import io
import json
import fitz
import psycopg2
import requests
import numpy as np
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from pydantic import BaseModel
from typing import List, Optional

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

# --- EMBDEDDING MODEL (Hugging Face API wrapper) ---
class HuggingFaceEmbedding:
    def __init__(self, token):
        self.api_url = "https://api-inference.huggingface.co/pipeline/feature-extraction/sentence-transformers/all-MiniLM-L6-v2"
        self.headers = {"Authorization": f"Bearer {token}"}

    def encode(self, texts, **kwargs):
        is_single = isinstance(texts, str)
        if is_single:
            texts = [texts]
        
        response = requests.post(self.api_url, headers=self.headers, json={"inputs": texts})
        
        if response.status_code != 200:
            raise Exception(f"Hugging Face API Error: {response.text}")
            
        embeddings = np.array(response.json())
        
        if kwargs.get("normalize_embeddings"):
            # L2 normalization across rows
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            embeddings = np.where(norms > 0, embeddings / norms, embeddings)
            
        return embeddings[0] if is_single else embeddings

hf_token = os.getenv("HF_API_TOKEN")
print("Loading Hugging Face API Embedding Wrapper...")
embedding_model = HuggingFaceEmbedding(token=hf_token)

# REMOVED RERANKER: The cross-encoder takes too much RAM and exceeds Render's 512MB limit!
# reranker_model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')

# --- DB HELPERS ---
def get_db_conn():
    try:
        return psycopg2.connect(DB_URL)
    except Exception as e:
        print(f"FAILED TO CONNECT TO DB: {e}")
        raise e

def init_db():
    try:
        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS query_cache_mini (
                        id SERIAL PRIMARY KEY,
                        lecture_id VARCHAR(255),
                        question TEXT,
                        answer TEXT,
                        embedding vector(384)
                    )
                """)
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS documents_mini (
                        id uuid default gen_random_uuid() primary key,
                        content text,
                        metadata jsonb,
                        embedding vector(384)
                    )
                """)
            conn.commit()
            print("✅ DB Tables Initialized")
    except Exception as e:
        print(f"⚠️ Could not initialize DB Tables: {e}")

init_db()

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

class SaveCacheRequest(BaseModel):
    lecture_id: str
    question: str
    answer: str

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
                    "DELETE FROM documents_mini WHERE metadata->>'lecture_id' = %s",
                    (lecture_id,)
                )

                for chunk, embedding in zip(chunks, embeddings):
                    cur.execute(
                        "INSERT INTO documents_mini (content, embedding, metadata) VALUES (%s, %s::vector, %s)",
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
                    "SELECT content FROM documents_mini WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), SEARCH_LIMIT)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]

        if not retrieved_chunks:
            return QueryResult(answer="No content found", chunks=[])

        # REMOVED RERANKER for memory usage: just uses the raw vector similarity sorting
        top_chunks = retrieved_chunks[:RERANK_TOP_K]

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

        # Using Groq API
        groq_api_key = os.getenv("GROQ_API_KEY")
        if not groq_api_key:
            raise Exception("GROQ_API_KEY is not set in environment or .env file")
            
        groq_response = requests.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {groq_api_key}",
                "Content-Type": "application/json"
            },
            json={
                "model": "llama-3.3-70b-versatile",
                "messages": [
                    {"role": "user", "content": prompt}
                ]
            }
        )
        
        if groq_response.status_code != 200:
            raise Exception(f"Groq API Error: {groq_response.text}")
            
        answer = groq_response.json()["choices"][0]["message"]["content"]

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
                # 1. 🚀 CACHE LOOKUP optimization
                cur.execute(
                    "SELECT answer, (embedding <=> %s::vector) AS distance FROM query_cache_mini WHERE lecture_id = %s ORDER BY distance LIMIT 1",
                    (query_emb.tolist(), request.lecture_id)
                )
                row = cur.fetchone()
                # Cosine distance < 0.05 means ~95% similarity
                if row and row[1] < 0.05:
                    print(f"⚡ RAG CACHE HIT (Distance: {row[1]:.4f}) for lecture {request.lecture_id}")
                    return {"chunks": [], "cached_answer": row[0]}

                # 2. STANDARD VECTOR SEARCH
                cur.execute(
                    "SELECT content FROM documents_mini WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), SEARCH_LIMIT)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]

        if not retrieved_chunks:
            return {"chunks": []}

        # REMOVED RERANKER for memory usage: just uses the raw vector similarity sorting
        top_chunks = retrieved_chunks[:RERANK_TOP_K]

        # 🚀 OPTIMIZATION
        top_chunks = trim_chunks(top_chunks)

        return {"chunks": top_chunks}

    except Exception as e:
        print(f"Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# --- SAVE CACHE ---
@app.post("/save-cache")
async def save_cache(request: SaveCacheRequest):
    try:
        emb = embedding_model.encode(
            request.question,
            normalize_embeddings=True
        )
        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO query_cache_mini (lecture_id, question, answer, embedding) VALUES (%s, %s, %s, %s::vector)",
                    (request.lecture_id, request.question, request.answer, emb.tolist())
                )
            conn.commit()
            print(f"💾 Saved to RAG cache for lecture {request.lecture_id}")
        return {"message": "Cache saved"}
    except Exception as e:
        print(f"Error saving cache: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# --- DEBUG ---
@app.get("/debug/{lecture_id}")
async def debug(lecture_id: str):
    with get_db_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM documents_mini WHERE metadata->>'lecture_id' = %s",
                (lecture_id,)
            )
            count = cur.fetchone()[0]
    return {"chunks": count}

# --- RUN ---
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)