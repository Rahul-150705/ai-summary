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
RERANK_TOP_K = int(os.getenv("RERANK_TOP_K", 3))

# --- ML MODELS ---
# Embedding Model (BAAI/bge-large-en-v1.5 -> 1024 dims)
print("Loading BGE-Large-v1.5 Embedding Model...")
embedding_model = SentenceTransformer('BAAI/bge-large-en-v1.5')

# Reranker Model (CrossEncoder -> MS-MARCO)
print("Loading Cross-Encoder Reranker...")
reranker_model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')

# --- DB HELPERS ---
def get_db_conn():
    """Returns a direct connection to the Neon postgres DB."""
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

# --- CHUNKING & PROCESSING ---
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

# --- ENDPOINTS ---

@app.post("/add")
async def add_document(lecture_id: str = Form(...), file: UploadFile = File(...)):
    """
    1. Check for existing chunks (Cache logic)
    2. Extract PDF Text
    3. Chunk & Embed (BGE-Large)
    4. Store in Neon (pgvector)
    """
    try:
        # Step 2: Extract PDF
        content = await file.read()
        pdf_doc = fitz.open(stream=io.BytesIO(content), filetype="pdf")
        full_text = ""
        for page in pdf_doc:
            full_text += page.get_text()

        chunks = chunk_text(full_text)
        print(f"Indexing {len(chunks)} new chunks for lecture_id={lecture_id}...")
        
        embeddings = embedding_model.encode(chunks)

        with get_db_conn() as conn:
            with conn.cursor() as cur:
                # DELETE stale data first — ensures clean re-index every time
                cur.execute(
                    "DELETE FROM documents WHERE metadata->>'lecture_id' = %s",
                    (lecture_id,)
                )
                deleted = cur.rowcount
                if deleted > 0:
                    print(f"Deleted {deleted} stale chunks for lecture_id={lecture_id}")

                for chunk, embedding in zip(chunks, embeddings):
                    cur.execute(
                        "INSERT INTO documents (content, embedding, metadata) VALUES (%s, %s::vector, %s)",
                        (chunk, embedding.tolist(), json.dumps({"lecture_id": lecture_id}))
                    )
                conn.commit()
                print(f"RAG Indexing: Successfully saved {len(chunks)} chunks for lectureId={lecture_id}")

        return {"message": "Success (indexed new chunks)", "chunks_indexed": len(chunks)}
    except Exception as e:
        print(f"Error in /add: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/query", response_model=QueryResult)
async def query_rag(request: QueryRequest):
    """
    1. Embed query
    2. Retrieve Top 8 from pgvector
    3. Rerank to Top 3 using CrossEncoder
    4. Ground answer with local Ollama
    """
    try:
        # Step 1: Embed Query
        query_emb = embedding_model.encode(request.question)

        # Step 2: Retrieve Top 8
        with get_db_conn() as conn:
            with conn.cursor() as cur:
                # Ordering by Cosine Distance (<=> operator)
                # We filter by lecture_id (which is our content fingerprint)
                cur.execute(
                    "SELECT content FROM documents WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), SEARCH_LIMIT)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]
                print(f"RAG Query: Found {len(retrieved_chunks)} relevant chunks for fingerprint={request.lecture_id}")

        if not retrieved_chunks:
            return QueryResult(answer="I don't know (No relevant content found).", chunks=[])

        # Step 3: Re-rank Top 8 -> Top 3
        # Format pairs for CrossEncoder
        pairs = [[request.question, chunk] for chunk in retrieved_chunks]
        scores = reranker_model.predict(pairs)
        
        # Sort chunks by score (highest first)
        ranked_chunks = [chunk for _, chunk in sorted(zip(scores, retrieved_chunks), key=lambda x: x[0], reverse=True)]
        top_3_chunks = ranked_chunks[:RERANK_TOP_K]

        # Step 4: LLM Generation with Teaching Assistant Prompt
        context = "\n\n---\n\n".join(top_3_chunks)
        prompt = f"""You are an expert teaching assistant helping a student understand their lecture material.

Use ONLY the context below to answer the question. Be specific and educational.
If the answer is not in the context, say "This topic isn't covered in the provided lecture material."
Do not make up facts. Do not use outside knowledge.

LECTURE CONTEXT:
{context}

STUDENT QUESTION:
{request.question}

ANSWER (be clear, specific, and explain concepts thoroughly):"""

        ollama_response = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={
                "model": "llama3.2:latest",
                "prompt": prompt,
                "stream": False
            }
        )
        
        if ollama_response.status_code != 200:
             raise HTTPException(status_code=500, detail="Ollama error")

        answer = ollama_response.json().get("response", "No response from AI")

        return QueryResult(answer=answer, chunks=top_3_chunks)

    except Exception as e:
        print(f"Error in /query: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/retrieve-context")
async def retrieve_context(request: RetrieveContextRequest):
    """
    Returns only the top RERANK_TOP_K chunks relevant to the specific user question.
    Intended for delegating the LLM generation to the Java backend for streaming.
    """
    try:
        query_emb = embedding_model.encode(request.question)
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
        scores = reranker_model.predict(pairs)
        ranked = [chunk for _, chunk in sorted(zip(scores, retrieved_chunks), key=lambda x: x[0], reverse=True)]
        top_chunks = ranked[:RERANK_TOP_K]
        return {"chunks": top_chunks}
    except Exception as e:
        print(f"Error in /retrieve-context: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/quiz-context")
async def get_quiz_context(request: QueryRequest):
    """
    Returns top 8 most relevant chunks for quiz generation.
    No Ollama call — just retrieval + reranking.
    """
    try:
        # Use a broad query to find key educational content
        query_emb = embedding_model.encode(
            "main topics key concepts important facts definitions"
        )

        with get_db_conn() as conn:
            with conn.cursor() as cur:
                # Retrieve slightly more for reranking
                cur.execute(
                    "SELECT content FROM documents WHERE metadata->>'lecture_id' = %s ORDER BY embedding <=> %s::vector LIMIT %s",
                    (request.lecture_id, query_emb.tolist(), 15)
                )
                retrieved_chunks = [row[0] for row in cur.fetchall()]

        if not retrieved_chunks:
            return {"chunks": [], "context": ""}

        # Rerank with a broad educational query to get the best 8
        pairs = [["important concepts facts definitions examples", chunk] 
                 for chunk in retrieved_chunks]
        scores = reranker_model.predict(pairs)
        ranked = [chunk for _, chunk in sorted(
            zip(scores, retrieved_chunks), key=lambda x: x[0], reverse=True
        )]
        top_chunks = ranked[:8]  # Top 8 for broad quiz coverage
        
        return {
            "chunks": top_chunks,
            "context": "\n\n---\n\n".join(top_chunks)
        }

    except Exception as e:
        print(f"Error in /quiz-context: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/debug/{lecture_id}")
async def debug(lecture_id: str):
    try:
        with get_db_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT COUNT(*) FROM documents WHERE metadata->>'lecture_id' = %s",
                    (lecture_id,)
                )
                count = cur.fetchone()[0]
        return {"lecture_id": lecture_id, "chunks_stored": count}
    except Exception as e:
        print(f"Error in /debug: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)
