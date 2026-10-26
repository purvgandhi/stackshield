# StackShield · Codebase Analyzer for Java

StackShield is a modern developer tool for analyzing Java codebases, identifying security risks, calculating code quality metrics, and detecting version upgrade regressions.

---

## Features

- **Static Codebase Analysis**: Deep, deterministic inspection across 5 categories: Security Risks, Code Quality, Dependencies, Configuration, and Improvement Suggestions.
- **Visual Graphs**: Interactive findings breakdown (category horizontal bar chart and SVG severity donut chart) with instant filtering.
- **Docker Verification Sandbox**: Parallel isolated container trial verification for dependency and framework version upgrades.
- **Modern BRIX Lavender Theme**: Soft lavender backgrounds (`#F4F0FF`), clean white cards, rich purple accents (`#6C4BEF`), and micro-interactions without AI fluff or emojis.

---

## Deployments

### 1. Vercel Deployment (Instant Interactive UI)
This repository includes a native `vercel.json` configuration and static web distribution in `/public`.
1. Push this repository to your GitHub account.
2. In [Vercel Dashboard](https://vercel.com/new), click **Add New Project** and select this repository.
3. Keep default settings (`Framework Preset: Other`, `Output Directory: public` or root).
4. Click **Deploy**. Vercel will deploy the interactive StackShield UI in seconds.

### 2. Render / Railway / Docker (Full Spring Boot + PostgreSQL Backend)
To run the full live Java backend with PostgreSQL:
1. Use the included root `Dockerfile`.
2. Provision a PostgreSQL instance (PostgreSQL 16).
3. Set the environment variables:
   - `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
   - `SPRING_DATASOURCE_USERNAME=<user>`
   - `SPRING_DATASOURCE_PASSWORD=<password>`
4. Deploy the Docker container.
