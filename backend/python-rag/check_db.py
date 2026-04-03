import os
import psycopg2
import json
from dotenv import load_dotenv

load_dotenv()
DB_URL = os.getenv("DB_URL")

def check_brain():
    """Checks the contents of the AI documents table."""
    try:
        conn = psycopg2.connect(DB_URL)
        cur = conn.cursor()
        
        # 1. Total counts
        cur.execute("SELECT COUNT(*) FROM documents")
        total = cur.fetchone()[0]
        print(f"🧠 Total knowledge chunks: {total}")
        
        # 2. Distinct fingerprints (lecture_ids)
        cur.execute("SELECT DISTINCT metadata->>'lecture_id' FROM documents")
        fingerprints = [row[0] for row in cur.fetchall()]
        print(f"🏷️  Available Fingerprints: {fingerprints}")
        
        # 3. Last inserted 5 IDs from the lectures table
        cur.execute("SELECT id, content_hash FROM lectures ORDER BY processed_at DESC LIMIT 5")
        lectures = cur.fetchall()
        print("\n📚 Latest Lectures (History):")
        for lid, chash in lectures:
            print(f"   ID: {lid} | Hash: {chash}")

        conn.close()
    except Exception as e:
        print(f"❌ DB Check Failed: {e}")

if __name__ == "__main__":
    check_brain()
