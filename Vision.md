# Preflight – IntelliJ Plugin

## Idee

Preflight ist ein IntelliJ Plugin das einen lokalen, AI-gestützten Code-Review-Workflow ermöglicht – bevor ein Pull Request überhaupt erstellt wird.

## Problem

IntelliJ bietet keinen einfachen Weg, alle Änderungen eines Branches gegenüber dem Main-Branch als zusammengefasste Diff-Ansicht zu sehen. Man muss entweder commit-by-commit durch den Git-Log scrollen oder auf GitHub/GitLab gehen. Außerdem gibt es keinen lokalen Review-Loop mit AI.

## Core Features

### 1. Branch Diff Viewer
- Zeigt alle Änderungen des aktuellen Branches gegenüber seinem Merge-Base (z.B. `main`)
- Quasi der "Files changed" Tab eines GitHub PRs – aber lokal in IntelliJ
- Nutzt IntelliJs eingebauten Diff-Viewer, Syntax Highlighting und Dateibaum

**Technisch:**
- `git merge-base main HEAD` ermitteln
- Diff von diesem Punkt zu HEAD berechnen
- In IntelliJs Diff-Viewer anzeigen

### 2. Inline Kommentare
- Der Nutzer kann Kommentare an einzelnen Zeilen im Diff hinterlassen
- Kommentare sind strukturiert: Datei, Zeile, Kommentar-Text

**Datenstruktur:**
```json
[
  {
    "file": "src/Foo.kt",
    "line": 42,
    "comment": "Diese Methode ist zu lang, aufteilen?"
  }
]
```

### 3. Claude Code Review Loop
Der Kern des Plugins: ein Review-Loop mit Claude Code als Gegenüber.

**Ablauf:**
1. Nutzer hinterlässt Kommentare auf dem Diff
2. Kommentare werden strukturiert an Claude Code übergeben (zusammen mit dem Diff-Kontext)
3. Claude antwortet pro Kommentar: **lösen / ablehnen / diskutieren**
4. Wenn "lösen": Claude erstellt zuerst einen **Plan** (was würde es ändern und warum)
5. Nutzer **nimmt den Plan an** oder diskutiert weiter
6. Erst nach Annahme führt Claude die Änderung aus
7. Kommentar wird als erledigt markiert

## Design-Prinzipien

- **Kontrolle beim Nutzer:** Claude ändert nie Code ohne vorherige Planung und Zustimmung
- **Kein eigenes Styling nötig:** Alles baut auf IntelliJs nativer UI auf
- **Lokal first:** Kein GitHub/GitLab nötig, funktioniert komplett offline (außer Claude API)

## Tech Stack

- **Sprache:** Kotlin
- **Basis:** IntelliJ Platform Plugin Template (GitHub)
- **Git-Integration:** IntelliJ Git4Idea API
- **Diff-Viewer:** IntelliJs eingebauter DiffManager
- **Inline Kommentare:** Inlay Hints / EditorCustomElementRenderer
- **Claude-Integration:** Claude Code CLI (Kommentare + Diff als strukturierter Input)
- **UI:** Tool Window für Review-Chat/Kommentar-Übersicht

## Abgrenzung zu bestehenden Tools

| Tool | Lücke |
|------|-------|
| IntelliJ Git Log | Commit-by-commit, keine Gesamtübersicht |
| JetBrains GitHub Plugin | Nur nach PR-Erstellung, nicht lokal |
| Claude Code CLI | Kein Diff-Viewer, keine strukturierten Inline-Kommentare |

## Nächste Schritte

1. IntelliJ Platform Plugin Template klonen
2. Tool Window mit Branch-Diff-Ansicht bauen
3. Inline-Kommentar-System implementieren
4. Claude Code Integration (Kommentare übergeben, Antworten anzeigen)
5. Plan-Annahme-Flow implementieren