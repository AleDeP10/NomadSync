# 📓 Obsidian Portfolio Vault

This repository is the **public knowledge base** of an ongoing learning and development journey,
built around [ObsidianSync](https://github.com/AleDeP10/ObsidianSync) — a self-built Java CLI tool
that automates Git-based synchronization of an Obsidian vault across multiple Windows machines.

The vault documents the full lifecycle of the project: architecture decisions, sprint planning,
milestone retrospectives, and scored self-assessments — structured as a real-world Agile workflow.

---

## Why this exists

Most portfolios show the final product. This one shows **the process**.

Every decision has a reason. Every obstacle has a post-mortem. Every sprint has a score.
The goal is to demonstrate not just what was built, but *how* — the thinking, the trade-offs,
and the growth across iterations.

---

## Repository structure

```
obsidian-portfolio/
└── ObsidianSync Diary/
    ├── Decisions/          ← Architecture Decision Records (ADR-style)
    │   └── DEC_Milestone_1.md
    ├── Groomings/          ← Sprint planning sessions with risk analysis and know-how preparation
    │   └── GRM_Milestone_2.md
    ├── Milestones/         ← Sprint retrospectives: objectives, problems, cheat-sheets acquired
    │   └── MILESTONE_1.md
    └── Scores/             ← Recruiter-style assessments and personal trainer evaluations
        └── SCR_Milestone_2.md
```

---

## The project: ObsidianSync

> A lightweight Java CLI tool that automates Git-based sync of an Obsidian vault across
> Windows machines. Triggered on logon/logoff via Task Scheduler, it handles pull/push
> workflows, differential autosave, and conflict resolution with audit logging.

**Tech stack**: Java 21 · Maven · Git CLI via ProcessBuilder · Task Scheduler · Windows Batch

**Source code**: [github.com/AleDeP10/ObsidianSync](https://github.com/AleDeP10/ObsidianSync)

### Architecture highlights

- **Event-driven orchestration** — operations are published as prioritized events to a queue;
  the orchestrator consumes them serially, preventing Git concurrency issues
- **Exponential backoff retry** — network failures trigger up to 3 retries with progressive delays
- **Differential autosave** — commits only when `git diff` detects actual changes
- **Conflict resolution strategy** — `git pull -X theirs` with full audit logging
- **Dependency inversion** — `NotificationHook` interface decouples the orchestrator
  from any future notification implementation (tray icon, toast, etc.)

---

## How to read this vault

Start with **`DEC_Milestone_1.md`** to understand the architectural choices made before
writing a single line of code.

Then read **`MILESTONE_1.md`** for the retrospective: what was built, what broke,
and what was learned.

Follow with **`GRM_Milestone_2.md`** to see how the next sprint was planned — risks
identified, priorities set, unknowns acknowledged.

---

## About

Alessandro De Prato · FullStack Developer  
[Portfolio](https://aledep10.github.io) · [Portfolio Vault](https://github.com/AleDeP10/obsidian-portfolio) · [GitHub](https://github.com/AleDeP10) · [LinkedIn](https://www.linkedin.com/in/alessandro-de-prato)
