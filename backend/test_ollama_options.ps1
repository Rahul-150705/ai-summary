$body = @{
    model = "llama3.2:latest"
    prompt = "hi"
    stream = $false
    options = @{
        num_thread = 14
        num_ctx = 1024
        num_batch = 512
        mmap = $true
        low_vram = $true
        temperature = 0.3
        repeat_penalty = 1.1
    }
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/generate" -Method Post -Body $body -ContentType "application/json"
