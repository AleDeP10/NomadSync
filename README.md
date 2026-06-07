# NomadSync

**Keep your knowledge vault in sync across every machine — one tray icon, zero friction.**

NomadSync is a lightweight background service that brings Git-based synchronization to everyone — developers and non-developers alike. Manage your personal knowledge base, share curated information across your team, or maintain a living organizational wiki that every collaborator can contribute to. No Git expertise required. No subscription fees. Just a GitHub account and a folder.

---

## Built cross-platform from day one

NomadSync runs on **Windows, macOS, and Linux** without recompilation. The choice of the JVM is deliberate: one codebase, one artifact, every OS. The Git executable path is the only platform-specific configuration — everything else is standard Java. If Git runs on your machine, NomadSync runs on your machine.

---

## The big idea

Git was invented to let multiple people collaborate on the same files without losing each other's work. For twenty years, that superpower has been locked behind a command line and a steep learning curve, available only to software developers.

NomadSync breaks that lock.

By handling the entire Git protocol automatically — pull on logon, push on logoff, conflict resolution on sync, retry on network failure — NomadSync makes Git-powered collaboration available to anyone who can click a tray icon. Your notes, your team's knowledge base, your organization's shared context: all versioned, all backed by infrastructure you own, all free.

---

## How it works

NomadSync hooks into your session lifecycle and runs silently in the system tray. Every time you log on, it pulls the latest version of your vault from GitHub. Every time you log off, it commits and pushes your changes. In between, it autosaves local commits on a configurable schedule — so your work is always protected, even offline.

When you need immediate sync across devices, a single click on the tray icon triggers a full **SYNCHRONIZE** cycle: local commit → pull → conflict resolution → push. If Git detects a merge conflict, NomadSync handles it automatically — backing up your local state, saving the remote conflicting versions to a dedicated snapshot folder, and pushing a clean result. Fifty shades of Git, reduced to one button.

---

## Features

- **Cross-platform by design** — runs on Windows, macOS, and Linux; one JAR, one config line to set your Git path, done
- **Automatic pull on logon** — stash-aware, never drops uncommitted work
- **Automatic push on logoff** — commits whatever is open before you close the lid
- **Periodic autosave** — local commits on a configurable interval, no network required
- **One-click full sync** — complete bidirectional synchronization from the tray
- **Multi-vault support** — manage multiple independent vaults, each with its own GitHub repository, collaborators, credentials, and sync schedule; personal knowledge base and team wiki side by side
- **Team collaboration out of the box** — any GitHub Collaborator can contribute to a shared vault without touching a terminal; NomadSync handles the protocol on every machine
- **Conflict survival kit** — automatic backup snapshots, remote conflict archiving, and intelligent merge resolution keep every contributor's work intact
- **Priority event queue** — logon pull always runs first; concurrent requests are serialized and deduplicated automatically
- **Exponential backoff retry** — transient network failures are retried silently; unrecoverable errors surface as tray notifications
- **App-aware `.gitignore` management** — built-in pattern definitions for Obsidian, Dataview, Templater, and Logseq keep tool-specific cache and local state files out of your repository automatically
- **Structured logging** — every operation is logged locally and optionally streamed to a Seq server for searchable, per-vault diagnostics
- **Zero cloud vendor lock-in** — your vault lives in a standard GitHub repository you own and control

---

## Why Git, not OneDrive or Syncthing?

Git operations are atomic and synchronous. OneDrive writes files asynchronously while your tools have them open — a reliable recipe for corruption. Syncthing requires at least one device always on. Paid sync tiers solve the problem elegantly but introduce a vendor between you and your own knowledge.

NomadSync puts Git — the most battle-tested collaboration infrastructure in the history of software — directly in your hands, with no intermediaries and no lock-in. And it adds something no proprietary sync tool can offer: a complete, navigable history of every change ever made to your knowledge base, by anyone on your team.
