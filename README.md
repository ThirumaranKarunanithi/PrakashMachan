# PRAMETRA — Financial Integrity & Audit Intelligence Platform

*Evidence behind every number.*

Multi-client audit and GST risk-intelligence platform for CA firms (see the BRD .docx at repo root).

## Repository layout

| Folder | What it is |
|---|---|
| `phase0/` | Phase 0 design-partner package: partner kit, synthetic sample data (seeded anomalies), rule matrix, sample workpapers, security baseline, throwaway rules spike |
| `backend/` | **Product backend — Spring Boot 3 / Java 17 (Maven)** |
| `frontend/` | **Product frontend — React (Vite + TypeScript)** |
| `platform/` | Superseded TypeScript prototype of the import module — kept as the porting spec/reference; do not extend |

## Quick start

Backend: `cd backend && mvn spring-boot:run` (API on :8080)
Frontend: `cd frontend && npm install && npm run dev` (UI on :5173, proxies /api to :8080)
Tests: `cd backend && mvn test`

## Handover

See [HANDOVER.md](HANDOVER.md) for the full handover: what is built per BRD section, demo
credentials and walkthrough, operational notes, what is intentionally deferred, and next steps.
