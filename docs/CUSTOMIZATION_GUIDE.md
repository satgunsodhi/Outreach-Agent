# Adapting the Outreach Agent

If you want to adapt this AI-powered outreach pipeline for your own use (replacing the original author's data with your own), you need to update several files across the backend and frontend. This guide outlines every change required to adapt the system for someone else.

## 1. Resume and Context Data
The core knowledge base of the Agent is driven by JSON files.
* **`src/main/resources/data/master_resume.json`**: Replace all the existing experiences, projects, education, and skills with your own. Ensure you keep the JSON schema the same (tags, bullet points), as the `ResumeKnowledgeBaseTool` uses this exact structure.
* **`src/main/resources/data/project_deep_context.json`**: Update this file with in-depth technical context about your personal projects so the LLM has detailed talking points when generating cover letters or tailoring resume bullets.

## 2. Java Source Code Changes
Currently, the agent is hardcoded to adopt the persona of the original author. You will need to update the following classes:

* **Prompts and Persona**:
  * `src/main/java/com/outreach/agent/agent/CoverLetterAgent.java`: Change the system prompt instructions. Replace "Satgun Singh Sodhi" and references to being an ML engineer/intern with your own persona, role, and titles. Adjust the signature examples (e.g., `- Satgun Sodhi - ML intern application`).
  * Review other Agent interface definitions in `src/main/java/com/outreach/agent/agent/` for any hardcoded assertions about your role.
* **Email Signatures**:
  * `src/main/java/com/outreach/agent/service/BatchOutreachService.java`: Update the HTML email signature. Replace links to `https://satgunsodhi.vercel.app`, your LinkedIn, and GitHub profiles in the generated HTML.
* **Controller Logic (Optional)**:
  * `src/main/java/com/outreach/agent/controller/BatchOutreachController.java`: Ensure there are no filters hardcoding outgoing/internal test emails (e.g., `satgunsodhi@gmail.com`).

## 3. Configuration & Security
* **CORS Settings**:
  * Update `application.yml` (`allowed-origins: ...`) and/or `SecurityConfig.java` to remove `https://satgunsodhi.vercel.app` and replace it with your own frontend domain if you host the Next.js UI in production.
* **`.env` Variables**:
  * Copy `.env.example` to `.env`.
  * Set `SMTP_USERNAME` to your email.
  * Provide your OpenRouter/Gemini API keys.
* **Google API Credentials**:
  * The project integrates with Gmail Drafts and Google Drive via OAuth 2.0.
  * You must create your own Google Cloud Platform (GCP) project.
  * Enable the **Google Drive API** and **Gmail API**.
  * Download the `credentials.json` file and place it appropriately (as defined in `docs/examples/credentials.json.example`).
  * **Important:** Delete any existing user tokens inside the `tokens/` directory (`tokens/StoredCredential`), as these belong to the previous user. On the first run, the app will prompt your browser to authorize access to your Google account.

## 4. Frontend Customization
* **`frontend/`**: The Next.js web application acts as the Command Center. Search through the Next.js components in `frontend/app/` for any hardcoded references, titles, or meta tags and replace them with your preferred naming.
* Update `frontend/README.md` if you plan to share or deploy the UI separately.

## 5. HTML Resume Template
* **`src/main/resources/templates/resume.html`**: This Thalymeleaf template is compiled to an ATS-compliant PDF using Flying Saucer. If you want a different visual layout, change the CSS within this file, but ensure it remains **CSS 2.1 compliant**, as Flying Saucer does not support modern CSS3/Flexbox/Grid. Update any hardcoded footer or header links in the HTML.
