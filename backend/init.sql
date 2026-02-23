-- This file runs automatically when the PostgreSQL container starts for the first time.
-- It enables the pgvector extension which is required for RAG Q&A vector search.

CREATE EXTENSION IF NOT EXISTS vector;
