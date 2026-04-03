$body = @{
    model = "llama3.2:latest"
    prompt = "Translate Hello to French"
    stream = $false
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/generate" -Method Post -Body $body -ContentType "application/json"
