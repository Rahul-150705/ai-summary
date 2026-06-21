import os
import psycopg2
from dotenv import load_dotenv

load_dotenv()
DB_URL = os.getenv("DB_URL")

try:
    print("Connecting to Neon Database...")
    conn = psycopg2.connect(DB_URL)
    cur = conn.cursor()
    
    print("Dropping old table documents_mini...")
    cur.execute("DROP TABLE IF EXISTS documents_mini CASCADE;")
    
    print("Dropping old table query_cache_mini...")
    cur.execute("DROP TABLE IF EXISTS query_cache_mini CASCADE;")
    
    conn.commit()
    cur.close()
    conn.close()
    print("✅ Successfully dropped old tables from the database!")
except Exception as e:
    print(f"❌ Error: {e}")
