# AI-Driven Resume Customization Agent

An agentic Spring Boot system designed to automatically tailor a candidate's master resume to a specific Job Description (JD). It leverages LangChain4j and Google Gemini 2.5 Flash to intelligently select the most relevant experiences, format them into an ATS-compliant layout, generate a 1-page PDF using Thymeleaf & Flying Saucer, and email the resulting document directly to the recipient.

## 🚀 Key Features

- **Agentic Workflow:** Employs an iterative LLM-powered feedback loop (via LangChain4j Tool calling) to continually adjust the resume content until the generated PDF meets the strict 1-page layout constraints.
- **Automated Batch Outreach Pipeline:** Uses Jsoup to scrape target company websites, feeds the content to a **CompanyResearchAgent** to identify key facts/technologies, generates a personalized cover letter using a **CoverLetterAgent**, and tailors the resume dynamically.
- **Next-Working-Day Scheduler:** Calculates the next working day's 8:00 AM IST and schedules automated personalized outreach emails to be sent asynchronously in batches.
- **Campaign Persistence:** Tracks batch outreach campaigns and targets using **Spring Data JPA** and an **H2 in-memory database**.
- **HTML-to-PDF Engine:** Renders data into strict XHTML using Thymeleaf, then compiles it via Flying Saucer (OpenPDF) to produce layout-optimized, ATS-compliant PDF resumes under 300ms.
- **Modern Stack:** Built on Java 25, Spring Boot 3.4.0, LangChain4j 1.15.0, and completely Lombok-free for compatibility and performance.

---

## 🛠️ Tech Stack

- **Core:** Java 21+ (Compatible up to JDK 25)
- **Framework:** Spring Boot 3.4.0
- **AI Orchestration:** LangChain4j 1.15.0
- **LLM Providers:** Google AI Studio (gemini-2.5-flash) or OpenRouter (gpt-oss-120b:free)
- **Database:** Spring Data JPA + H2 in-memory database
- **Web Scraping:** Jsoup HTML Parser
- **PDF Generation:** Thymeleaf (HTML templates), JSoup (XHTML compliance), Flying Saucer PDF / OpenPDF
- **Secrets Management:** Spring Dotenv

---

## 📂 Project Structure

```text
outreach-agent/
├── pom.xml                                  # Maven Build Configuration
├── .env.example                             # Template for secret keys
└── src/
    └── main/
        ├── java/com/outreach/agent/
        │   ├── OutreachAgentApplication.java# Spring Boot Entrypoint
        │   ├── agent/                       # LangChain4j AiService & Config
        │   ├── config/                      # Properties & LLM Configurations
        │   ├── controller/                  # REST Controllers (/api/resume/generate)
        │   ├── dto/                         # Request/Response data models
        │   ├── model/                       # Domain POJOs mapping the Master Resume
        │   ├── service/                     # Business Logic (Email, PDF, Orchestration)
        │   └── tools/                       # LangChain4j @Tool definitions
        └── resources/
            ├── application.yml              # Spring Boot settings
            ├── data/
            │   └── master_resume.json       # The central JSON knowledge base
            └── templates/
                └── resume.html              # The Thymeleaf XHTML Resume Template
```

---

## ⚙️ Setup and Installation

### 1. Prerequisites
- **Java Development Kit (JDK):** Version 21 or higher (Tested on Java 25)
- **Maven:** Version 3.8+
- **Google AI Studio API Key:** [Get a free key here](https://aistudio.google.com/)
- **SMTP Credentials:** An App Password from your email provider (e.g., Gmail) to send automated emails.

### 2. Environment Variables
Create a `.env` file in the root directory (alongside `pom.xml`). You can copy the provided `.env.example`:

```bash
cp .env.example .env
```

Update your `.env` file with your credentials:
```properties
GEMINI_API_KEY=your_gemini_api_key_here
SMTP_USERNAME=your_gmail_address@gmail.com
SMTP_PASSWORD=your_gmail_app_password
# Optional: OpenRouter keys if you switch profiles
OPENROUTER_API_KEY=your_openrouter_key_here
```

### 3. Build the Application
Compile the project and download all dependencies via Maven:
```bash
mvn clean compile
```

---

## 🏃 Running the Application

You can start the Spring Boot server directly via the Maven plugin. The application will initialize Tomcat on port `8080`.

```bash
mvn spring-boot:run
```

---

## 🔌 API Usage

Once the application is running, you can interact with the agent via its REST APIs.

### 1. Single Resume Customization and Direct Mail
Generate a resume tailored to a JD and email it immediately.

**Endpoint:** `POST /api/resume/generate`

**Request Payload:**
```json
{
  "jobDescription": "We are looking for a Senior Java Engineer with strong experience in Spring Boot, microservices architecture, and event-driven systems like Kafka. AWS experience is a plus.",
  "recipientEmail": "recruiter@example.com",
  "subject": "Application for Senior Java Engineer",
  "coverLetterBody": "Please find my highly tailored resume attached."
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:8080/api/resume/generate \
-H "Content-Type: application/json" \
-d '{"jobDescription":"Looking for a Java Spring Boot developer with Kafka experience.", "recipientEmail": "test@test.com"}'
```

---

### 2. Batch Outreach Campaign (Scrape, Research, Tailor, Schedule)
Submit a list of target companies and emails. The application will scrape each company's website, perform LLM research to find key talking points, draft a tailored cover letter, generate a tailored resume, and schedule the outreach email for the **next working day at 8:00 AM IST** (asynchronous batch dispatch).

**Endpoint:** `POST /api/outreach/batch`

**Request Payload:**
```json
{
  "jobDescription": "We are looking for a Machine Learning engineer with computer vision and LLM deployment experience.",
  "targets": [
    {
      "companyName": "Sapiens",
      "companyUrl": "https://www.sapiens.com",
      "recipientEmail": "hr@sapiens.com"
    },
    {
      "companyName": "Reliance",
      "companyUrl": "https://www.ril.com",
      "recipientEmail": "recruiter@ril.com"
    }
  ]
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:8080/api/outreach/batch \
-H "Content-Type: application/json" \
-d '{"jobDescription":"Machine Learning Engineer role...", "targets":[{"companyName":"Sapiens","companyUrl":"https://www.sapiens.com","recipientEmail":"hr@sapiens.com"}]}'
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

### 3. Check Campaign Status
Retrieve the list of campaigns and their targets.

**Endpoint:** `GET /api/outreach/batch`

**cURL Example:**
```bash
curl http://localhost:8080/api/outreach/batch
```

---

## 📝 Customization

- **Updating the Master Resume:** Modify `src/main/resources/data/master_resume.json` to include your actual experiences, skills, and projects. Ensure you accurately "tag" and "prioritize" your bullet points so the LLM can make informed decisions when matching to a JD.
- **Modifying the Design:** The resume layout is defined in `src/main/resources/templates/resume.html`. Because it uses Flying Saucer, ensure that all modifications adhere strictly to **XHTML** and **CSS 2.1** standards. Modern layout features like Flexbox or Grid are not supported by the underlying rendering engine. 
