# OrynX Local Environment Setup Guide

This guide describes how to configure environment variables required to run OrynX microservices locally or in containerized environments.

---

## 1. Required Environment Variables

| Variable Name | Required By | Description | Example / Recommendation |
| :--- | :--- | :--- | :--- |
| `SPRING_DATASOURCE_USERNAME` | `auth-service`, `orchestrator-service`, `execution-service` | PostgreSQL database username | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | `auth-service`, `orchestrator-service`, `execution-service` | PostgreSQL database user password | *(Strong local password)* |
| `JWT_SECRET` | `auth-service` | Secret key for signing HS-256 JWT tokens (min 32 bytes / 256 bits) | *(At least 32 characters)* |
| `JWT_EXPIRATION` | `auth-service` | Token expiration in milliseconds (optional, defaults to `3600000` = 1 hour) | `3600000` |
| `NVIDIA_API_KEY` | `planning-service`, `orchestrator-service` | API key for NVIDIA NIM LLM inference endpoints | `nvapi-...` |
| `POSTGRES_PASSWORD` | Docker Compose (`postgres`) | Password initialized for PostgreSQL root container | Same as `SPRING_DATASOURCE_PASSWORD` |

---

## 2. Setting Environment Variables

### Windows PowerShell
To set environment variables in your current PowerShell session:
```powershell
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "your_secure_password"
$env:JWT_SECRET = "your-at-least-32-chars-long-secure-random-jwt-secret-key!"
$env:JWT_EXPIRATION = "3600000"
$env:NVIDIA_API_KEY = "your_nvidia_api_key"
```

To persist permanently in your Windows user profile:
```powershell
[System.Environment]::SetEnvironmentVariable("SPRING_DATASOURCE_USERNAME", "postgres", "User")
[System.Environment]::SetEnvironmentVariable("SPRING_DATASOURCE_PASSWORD", "your_secure_password", "User")
[System.Environment]::SetEnvironmentVariable("JWT_SECRET", "your-at-least-32-chars-long-secure-random-jwt-secret-key!", "User")
[System.Environment]::SetEnvironmentVariable("NVIDIA_API_KEY", "your_nvidia_api_key", "User")
```

### Linux / macOS (bash / zsh)
To set environment variables in your current terminal session:
```bash
export SPRING_DATASOURCE_USERNAME="postgres"
export SPRING_DATASOURCE_PASSWORD="your_secure_password"
export JWT_SECRET="your-at-least-32-chars-long-secure-random-jwt-secret-key!"
export JWT_EXPIRATION="3600000"
export NVIDIA_API_KEY="your_nvidia_api_key"
```

To persist permanently, add the export statements above to your `~/.bashrc` or `~/.zshrc`.

---

## 3. Using Docker Compose
1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` with your actual development credentials.
3. Run containers:
   ```bash
   docker compose up -d
   ```
   Docker Compose automatically loads `.env` and injects variables into service definitions.

---

## 4. Security Guardrails
- **NEVER** commit `.env` or any file containing real passwords or keys to Git.
- Root `.gitignore` automatically ignores `.env`, `.env.*`, and `*.env`.
- Any missing required variable will cause services to fail fast on startup with an explicit placeholder resolution error.
