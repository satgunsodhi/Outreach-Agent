# AI-Driven Resume Customization Agent

An agentic Spring Boot system designed to automatically tailor a candidate's master resume to a specific Job Description (JD). It leverages LangChain4j and Google Gemini 2.5 Flash to intelligently select the most relevant experiences, format them into an ATS-compliant layout, generate a 1-page PDF using Thymeleaf & Flying Saucer, and email the resulting document directly to the recipient.

## 🚀 Key Features

- **Agentic Workflow:** Employs an iterative LLM-powered feedback loop (via LangChain4j Tool calling) to continually adjust the resume content until the generated PDF meets the strict 1-page length requirement.
- **Knowledge Base Querying:** Automatically loads a comprehensive JSON-based `master_resume` and matches skills, project tags, and bullet points to the target Job Description.
- **HTML-to-PDF Engine:** Renders dynamic data into strict XHTML using Thymeleaf, then processes it through Flying Saucer (OpenPDF) to produce pristine, ATS-friendly PDF outputs.
- **Automated Delivery:** Packages the generated PDF and dispatches it via a built-in JavaMailSender SMTP integration.
- **Modern Stack:** Built on Java 25, Spring Boot 3.4.0, LangChain4j 1.15.0, and completely Lombok-free for maximum compatibility with newer JDKs.

---

## 🛠️ Tech Stack

- **Core:** Java 21+ (Compatible up to JDK 25)
- **Framework:** Spring Boot 3.4.0
- **AI Orchestration:** LangChain4j 1.15.0
- **LLM Provider:** Google AI Studio (gemini-2.5-flash)
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

Once the application is running, you can interact with the agent via its REST API.

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

The agent will:
1. Parse the provided JD.
2. Search `master_resume.json` for the most relevant bullet points.
3. Generate an HTML resume and convert it to a PDF.
4. Verify the PDF is exactly 1 page (and loop to shorten it if it's too long).
5. Dispatch the email with the PDF attached to the provided `recipientEmail`.

---

## 📝 Customization

- **Updating the Master Resume:** Modify `src/main/resources/data/master_resume.json` to include your actual experiences, skills, and projects. Ensure you accurately "tag" and "prioritize" your bullet points so the LLM can make informed decisions when matching to a JD.
- **Modifying the Design:** The resume layout is defined in `src/main/resources/templates/resume.html`. Because it uses Flying Saucer, ensure that all modifications adhere strictly to **XHTML** and **CSS 2.1** standards. Modern layout features like Flexbox or Grid are not supported by the underlying rendering engine. 
