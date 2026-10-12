param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$IncludeAnswers
)

$ErrorActionPreference = "Stop"

Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/rag/ingest" | Out-Null

$body = @{
    modes = @("vector", "hybrid", "hybrid-rerank")
    includeAnswers = [bool]$IncludeAnswers
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/rag/eval" `
    -ContentType "application/json" `
    -Body $body |
    ConvertTo-Json -Depth 12
