# Outreach Agent — Frontend Command Center

This is the Next.js-based web frontend for the **Outreach Agent** pipeline. It acts as the Command Center, allowing you to manually add, view, and manage outreach targets while the Spring Boot backend processes them asynchronously.

## Tech Stack
- **Framework**: [Next.js](https://nextjs.org) (App Router)
- **Styling**: Tailwind CSS v4
- **Database Access**: `pg` (Direct API routes linking to the Outreach DB)

## Getting Started

First, ensure you have your environment configured, then run the development server:

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

## Integration with Backend
The frontend relies on the Java Spring Boot backend running alongside it (typically on `http://localhost:8080`). It directly interacts with the backend's REST APIs (e.g. `/api/targets`, `/api/resume/generate`) to trigger campaigns and update statuses. 

Additionally, API routes within this Next.js app (e.g., `app/api/targets/route.js`) provide direct Database-level data access if configured properly.
