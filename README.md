# Outreach Agent 🤖

An autonomous AI-powered outreach pipeline built with **Java 21 + Spring Boot 3**. It scrapes company websites, researches them with an LLM, generates a tailored resume PDF, writes a personalized cover letter, uploads the resume to **Google Drive**, and schedules the outreach email for the next working day at 8:00 AM IST — all automatically.

---

## ✨ Key Features

| Feature | Description |
|---|---|
| **Agentic Resume Tailoring** | LangChain4j tool-calling loop iteratively adjusts content until the PDF fits exactly one page |
| **Company Research** | Jsoup scrapes target company websites; a `CompanyResearchAgent` extracts key talking points via LLM |
| **Command Center (Web UI)** | A decoupled React/Vite frontend for manually adding and managing outreach targets |
| **Autonomous Discovery** | When idle, a `TargetDiscoveryAgent` silently searches the web (e.g. DuckDuckGo) for new targets and automatically injects them into the pipeline |
| **Cover Letter Generation** | `CoverLetterAgent` writes a personalized cover letter grounded in real company research |
| **Placeholder Sanitization** | Multi-pass regex system detects and fills any LLM-generated placeholder tokens before sending |
| **Google Drive Upload** | Resumes are uploaded to a shared Drive folder via OAuth 2.0 and linked in the email |
| **Next-Working-Day Scheduler** | Emails are dispatched asynchronously at 8:00 AM IST on the next weekday |
| **Follow-up Automation** | Automatically sends a follow-up email if no reply after one working day |
| **Campaign Persistence** | Tracks all targets, statuses, and drafts via Spring Data JPA + PostgreSQL/H2 |
| **HTML-to-PDF Engine** | Thymeleaf → XHTML → Flying Saucer/OpenPDF, producing ATS-compliant single-page PDFs |

---

## 🛠️ Tech Stack

- **Core:** Java 21 / Spring Boot 3.4.0
- **AI Orchestration:** LangChain4j 1.15.0
- **LLM Provider:** OpenRouter (`openai/gpt-oss-120b:free` by default) or Google Gemini 2.5 Flash
- **PDF Generation:** Thymeleaf + Flying Saucer (OpenPDF)
- **Web Scraping:** Jsoup
- **Database:** Spring Data JPA + H2 (file-based, persisted at `./data/outreachdb`)
- **File Storage:** Google Drive API v3 (OAuth 2.0 Installed App flow)
- **Email:** Gmail API (OAuth 2.0)
- **Secrets:** Spring Dotenv (`.env` file support)

---

## 📂 Project Structure

```text
outreach-agent/
├── pom.xml                                  # Maven build configuration
├── .env.example                             # Template for secrets — copy to .env
└── src/main/
    ├── java/com/outreach/agent/
    │   ├── OutreachAgentApplication.java    # Spring Boot entry point
    │   ├── agent/                           # LangChain4j AiService interfaces
    │   │   ├── CompanyResearchAgent.java
    │   │   └── CoverLetterAgent.java
    │   ├── config/                          # LLM + properties configuration
    │   ├── controller/                      # REST API controllers
    │   │   ├── BatchOutreachController.java
    │   │   └── ResumeController.java
    │   ├── dto/                             # Request / response DTOs
    │   ├── model/                           # JPA entities + resume domain models
    │   ├── repository/                      # Spring Data JPA repositories
    │   ├── service/                         # Business logic
    │   │   ├── BatchOutreachService.java    # Main pipeline scheduler
    │   │   ├── EmailAutomationService.java  # Email interface
    │   │   ├── GoogleDriveService.java      # Drive upload (OAuth 2.0)
    │   │   ├── MasterResumeService.java     # Loads master_resume.json
    │   │   ├── PdfGeneratorService.java     # HTML → PDF generation
    │   │   ├── ProjectDeepContextService.java
    │   │   └── ResumeOrchestrationService.java
    │   └── tools/                           # LangChain4j @Tool definitions
    ├── resources/
    │   ├── application.yml                  # Spring Boot configuration
    │   ├── data/
    │   │   ├── master_resume.json           # Your resume knowledge base
    │   │   └── project_deep_context.json    # Per-project detail for the LLM
    │   └── templates/
    │       └── resume.html                  # Thymeleaf XHTML resume template
    └── frontend/                            # Decoupled React + Vite Web UI
        ├── src/
        │   ├── App.jsx                      # Main UI and auth logic
        │   └── index.css                    # Glassmorphic vanilla CSS
```

---

## ⚙️ Setup

### Prerequisites
- Java 21+ (tested on Java 21 LTS)
- Maven 3.8+
- A Gmail account with OAuth enabled (Desktop app flow)
- An [OpenRouter](https://openrouter.ai/) API key (free tier available)
- A Google Cloud project with **Google Drive API** enabled

### 1. Clone and configure environment

```bash
git clone https://github.com/your-username/outreach-agent.git
cd outreach-agent
cp .env.example .env
```

Edit `.env` with your actual credentials (see the table below). Example files for ignored runtime paths live in `docs/examples/`.

### 2. Environment Variables

| Variable | Required | Description |
|---|---|---|
| `OPENROUTER_API_KEY` | ✅ | Your OpenRouter API key |
| `OPENROUTER_MODEL_NAME` | Optional | Defaults to `openai/gpt-oss-120b:free` |
| `GEMINI_API_KEY` | Optional | Only needed if using the `gemini` Spring profile |
| `GMAIL_ADDRESS` | ✅ | Gmail address used as the From: header |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | ✅ | Full contents of your OAuth 2.0 client secret JSON (see `docs/examples/credentials.json.example`) |
| `GOOGLE_DRIVE_FOLDER_ID` | ✅ | Target Drive folder ID for resume uploads |
| `GOOGLE_DRIVE_TOKENS_DIR` | Optional | Path to store OAuth tokens (default: `tokens/`) |
| `GOOGLE_REFRESH_TOKEN` | Optional | Refresh token for headless CI batch mode |
| `CI_BATCH_MODE` | Optional | Set `true` to load targets from file and run a one-shot batch |
| `TARGETS_FILE` | Optional | Path to targets JSON file (default: `targets.json`) |
| `DATABASE_URL` | Optional | PostgreSQL JDBC URL (used with `production` profile) |
| `DATABASE_USERNAME` | Optional | PostgreSQL username (used with `production` profile) |
| `DATABASE_PASSWORD` | Optional | PostgreSQL password (used with `production` profile) |
| `APP_SECURITY_USERNAME` | Optional | Admin username for Web UI (default: `admin`) |
| `APP_SECURITY_PASSWORD` | Optional | Admin password for Web UI (default: `admin123`) |

### 3. Google Drive OAuth 2.0 Setup

The application uses **OAuth 2.0 (Installed App)** for Drive access. On first run, it will open a browser to complete authorization and save credentials to the `tokens/` directory.

1. Go to [Google Cloud Console](https://console.cloud.google.com/) → **APIs & Services** → **Library** → Enable **Google Drive API**
2. Go to **Credentials** → **Create Credentials** → **OAuth client ID** → Application type: **Desktop app**
3. Download the JSON, copy its full contents into `GOOGLE_SERVICE_ACCOUNT_JSON` in your `.env`
4. **Run the app locally once** to complete the browser-based authorization flow
5. The `tokens/` directory is then populated with the `StoredCredential` file

> [!IMPORTANT]
> When deploying to a headless server, copy your local `tokens/` directory to the server before starting. The app will use the stored credential without needing a browser.

### 3.1 CI Batch Mode (Optional)

Set `CI_BATCH_MODE=true` and provide `TARGETS_FILE` to load targets from a JSON file and run a one-shot batch. For headless runs, set `GOOGLE_REFRESH_TOKEN` and keep `GOOGLE_SERVICE_ACCOUNT_JSON` configured.

### 3.2 Production Database (Optional)

If you want PostgreSQL instead of H2, set `spring.profiles.active=production` and configure `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`.

### 4. Customize Your Resume

Edit `src/main/resources/data/master_resume.json` with your actual experiences, skills, and projects. Use the `tags` and `priority` fields on each bullet point to help the LLM make good selection decisions.

---

## 🏃 Running Locally

**Backend (Spring Boot):**
```bash
mvn spring-boot:run
```
The application backend starts at `http://localhost:8080`.

**Frontend (React/Vite):**
```bash
cd frontend
npm install
npm run dev
```
The frontend starts at `http://localhost:5173`. Open this in your browser to access the Command Center.

---


## 🔌 API Reference

### `POST /api/outreach/batch` — Start a batch outreach campaign

Submit a list of target companies. The agent will scrape each website, draft a tailored resume + cover letter, and schedule emails for the next working day.

**Request:**
```json
{
  "jobDescription": "Looking for a Machine Learning engineer with LLM deployment experience.",
  "targets": [
    {
      "companyName": "Acme Corp",
      "companyUrl": "https://acmecorp.com",
      "recipientEmail": "hiring@acmecorp.com"
    }
  ]
}
```

**Response:**
```json
{
  "campaignId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "status": "PROCESSING",
  "message": "Batch outreach campaign scheduled successfully."
}
```

---

### `GET /api/outreach/batch` — Check campaign status

```bash
curl http://localhost:8080/api/outreach/batch
```

---

### `GET /api/outreach/targets` — List all targets and their statuses

```bash
curl http://localhost:8080/api/outreach/targets
```

---

### `POST /api/resume/generate` — Generate a single tailored resume and email it

**Request:**
```json
{
  "jobDescription": "Senior Java Engineer with Spring Boot and Kafka experience.",
  "recipientEmail": "recruiter@example.com",
  "subject": "Application for Senior Java Engineer",
  "coverLetterBody": "Please find my tailored resume attached."
}
```

---

## 📝 Customization

- **Resume Content:** Edit `src/main/resources/data/master_resume.json`. Tag bullet points with relevant skills and set `priority` (higher = more likely to be selected by the LLM).
- **Resume Layout:** Modify `src/main/resources/templates/resume.html`. This file is rendered by Flying Saucer — use only **XHTML + CSS 2.1**. Flexbox and CSS Grid are not supported.
- **Scheduling:** The `@Scheduled` intervals in `BatchOutreachService` can be adjusted (draft processing: every 10 seconds, dispatch: every minute, follow-ups: every hour).
- **LLM Profile:** Switch between OpenRouter and Gemini by setting `spring.profiles.active` in `application.yml` (`openrouter` or `gemini`).
- **Logging & Privacy:** The application uses SLF4J and masks sensitive data (like recipient emails and company names) in `INFO` logs by default, logging only `Target ID`. Detailed data is still logged at the `DEBUG` level. You can enable debug logging by setting `logging.level.com.outreach.agent=DEBUG` in `application.yml`.

---

## 🚀 Deployment

## 🚀 Deployment

### Backend (Railway/Render)
1. Push this repo to GitHub
2. Connect to Railway/Render → **Build with Maven (Java 21)**
3. Set all environment variables from `.env.example` in the platform dashboard.
4. Set CORS origins if necessary.
5. Copy your local `tokens/` folder contents to the server before first start (to avoid needing a browser for OAuth)

### Frontend (Vercel with Next.js)
1. Import your GitHub repository to Vercel.
2. In the Vercel project settings, set the **Root Directory** to `frontend`.
3. Vercel will auto-detect Next.js.
4. **Environment Variables**: You must provide the following to Vercel:
   - `DATABASE_URL`: Your Neon PostgreSQL connection string (so Next.js can read/write targets).
   - `ADMIN_PASSWORD`: A secure password of your choice to log into the Command Center.

---

## 📄 License

MIT
