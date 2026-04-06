# Use lightweight Python image
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Copy files
COPY . .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Expose port (Railway uses $PORT)
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port $PORT"]