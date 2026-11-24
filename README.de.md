# Agent Studio

[English](README.md) · [简体中文](README.zh-CN.md) · **Deutsch**

## Ausführbarer Agent-Editor

Fünf Fachwerkzeuge lassen sich auf der Arbeitsfläche konfigurieren und versioniert veröffentlichen. Desktop und mobile Ansicht verwenden dieselbe Version. Der Verlauf zeigt Quellen, Modell- und Werkzeugaufrufe, Schleifen, Freigaben, gespeicherte Ergebnisse und geschätzte Kosten. [Installation und Konfiguration](agent-workbench/README.md) · [Online-Arbeitsfläche](https://aitools.ynsmhy.com/open-source/)

Mit Agent Studio lassen sich KI-Workflows für einen einzelnen Benutzer auf dem eigenen Rechner erstellen und ausführen. Auf der Vue-Flow-Oberfläche verbindest du Schritte, ordnest Eingabefelder zu und legst fest, wann ein Mensch eine Freigabe erteilen soll. Das Java-Backend verwaltet Versionen und setzt wartende Durchläufe fort. Modellknoten verwenden Spring AI 2.0.3. Die beiden Beispiele kannst du ohne API-Schlüssel ausprobieren.

| Lokaler Workflow | Was du ausprobieren kannst |
| --- | --- |
| [Aufgaben aus Besprechungen](http://127.0.0.1:5173/#/apps/meeting-notes) | Extrahiere Aufgaben aus einem Protokoll und filtere nach zugewiesener Person. Sobald die Mindestanzahl erreicht ist, prüfst du die Aufgaben. Deine Auswahl wird im lokalen Ausführungsprotokoll gespeichert. |
| [Feedback sortieren](http://127.0.0.1:5173/#/apps/feedback-triage) | Leite Rückmeldungen anhand konfigurierbarer Schlüsselwörter weiter. Dringende Meldungen werden geprüft und nach Freigabe als lokales Ticket gespeichert. Bei gewöhnlichen Vorschlägen endet der Durchlauf direkt. |
| [Früherer Besprechungsworkflow](http://127.0.0.1:5173/#/legacy) | Öffne die ursprüngliche Vorlage, Entwürfe, ausstehende Freigaben und die Aufgabenvorschau. Aus einer früheren Konfiguration lässt sich ein schreibgeschützter Graph anzeigen oder kopieren. |

Beide aktuellen Workflows verwenden denselben Editor und dieselbe Laufzeitumgebung. Für jeden gibt es eine veröffentlichte Version zum Ausprobieren. Die Position eines Knotens bestimmt sein Aussehen im Diagramm. Die Verbindungen (`from / port / to`) legen die Reihenfolge fest, die Feldzuordnungen die Herkunft der Eingaben.

## Lokal starten

Du brauchst JDK 17, Maven 3.9, npm und Node.js 22.12 oder 24 LTS. Öffne zwei Terminals im Stammverzeichnis des Repositorys.

Wähle vor dem ersten Start einen lokalen Datenbanknamen für den Benutzer und ein starkes Passwort. Bewahre beides im Passwortmanager auf und verwende beim erneuten Öffnen derselben H2-Datenbank dieselben Zugangsdaten. Für MySQL verwendest du das Konto deiner eigenen Datenbank. Gib die Zugangsdaten im Backend-Terminal ein, ohne das Passwort in einen Befehl oder eine Datei im Repository zu schreiben.

PowerShell 7.1+

```powershell
$env:STUDIO_DB_USERNAME = Read-Host 'Database user'
$env:STUDIO_DB_PASSWORD = Read-Host 'Database password' -MaskInput
```

Bash

```bash
read -r -p 'Database user: ' STUDIO_DB_USERNAME
read -r -s -p 'Database password: ' STUDIO_DB_PASSWORD
printf '\n'
export STUDIO_DB_USERNAME STUDIO_DB_PASSWORD
```

Starte im ersten Terminal das Backend. Es lauscht auf `127.0.0.1:8080`. Beim ersten Start legt Flyway die Tabellen an und lädt beide Workflow-Graphen, ihre veröffentlichten Versionen, die Testbeispiele und den Entwurf des früheren Besprechungsworkflows.

```sh
cd backend
mvn spring-boot:run
```

Starte im zweiten Terminal das Frontend und öffne anschließend den [lokalen Editor](http://127.0.0.1:5173). Der Entwicklungsserver leitet Anfragen an `/api` an das Backend weiter.

```sh
cd frontend
npm ci
npm run dev
```

### Den ersten Durchlauf ausprobieren

1. Öffne im Besprechungsworkflow **运行与恢复** (Ausführen und fortsetzen) und starte **含负责人和待分配事项** (zugewiesene und offene Aufgaben). Der voreingestellte Filter behält Alices Aufgabe. Sobald der Durchlauf wartet, lade die Seite neu oder öffne die URL erneut. Wähle die Aufgabe aus, bestätige sie und prüfe den Ausführungspfad sowie die gespeicherte Aufgabe.
2. Starte im Feedback-Workflow **紧急反馈** (dringende Meldung) und **普通建议** (gewöhnlicher Vorschlag). Bei Freigabe der dringenden Meldung entsteht ein lokales Ticket. Der gewöhnliche Vorschlag durchläuft den Workflow ohne Freigabe. Über die Vorgangskennung kannst du das Ergebnis prüfen. Beide Beispiele laufen ohne externe Ticket- oder Nachrichtendienste.
3. Erhöhe im Editor **流程编排** die Mindestanzahl im Filter von eins auf zwei. Hier kannst du auch Knoten hinzufügen, Verbindungen ändern und Felder zuordnen. Speichere den Entwurf und vergleiche dieselben Beispiele unter **对比与发布** (Vergleichen und veröffentlichen). Bei nur einer zugewiesenen Aufgabe kann die Freigabe nun entfallen. Prüfe vor der Veröffentlichung, ob Ablauf und Testbedingungen dem gewünschten Verhalten entsprechen. Laufende Vorgänge behalten ihre ursprüngliche Version.

### Knoten testen und Workflows veröffentlichen

Wähle in den Knoteneinstellungen **调试此节点** (Diesen Knoten testen). Fixiere eine Eingabe und teste damit die veröffentlichte und die bearbeitete Version. Vergleiche Feldzuordnungen, Ausgaben, Verzweigungen, geplante Aktionen und Laufzeiten. Als Eingabe kannst du die Ausgabe eines bereits getesteten Vorgängerknotens verwenden oder in der Verlaufsansicht ausdrücklich gespeicherte Daten auswählen. Knotentests und Prüfungen vor der Veröffentlichung zeigen geplante Aktionen als Vorschau. Vollständige Durchläufe speichern lokale Vorgänge, auch bei Beispieldaten.

Mit Beispielen kannst du Filter, Verzweigungen und Freigaberegeln testen. Um zwei Prompts mit demselben Protokoll zu vergleichen, aktiviere Modellaufrufe und öffne `extract-tasks`. Bestätige den Modelltest für jede Version einzeln. Vergleiche Ausgaben und die vom Anbieter gemeldeten Verbrauchsdaten. Jeder Aufruf kann Kosten verursachen. Die Feedback-Sortierung verwendet Schlüsselwörter.

Entwürfe und Testsammlungen haben eigene Revisionsnummern. Führe vor der Veröffentlichung die gespeicherten Testfälle mit dem aktuellen gespeicherten Graphen aus. Lade bei einem Bearbeitungskonflikt die neueste Revision. Jede veröffentlichte Version hält Knoten, Verbindungen, Feldzuordnungen, Einstellungen und die Versionen der verwendeten Funktionen fest. [Speicherung, Wiederaufnahme und API (Chinesisch)](docs/storage-and-api.md) · [Eigene Knoten hinzufügen (Chinesisch)](docs/node-extensions.md)

## Quellcode und Datenspeicherung

| Pfad | Inhalt |
| --- | --- |
| `backend/src/main/resources/db/migration/` | Flyway-Migrationen für frühere und aktuelle Graphen, veröffentlichte Versionen, Durchläufe, Verbrauchs- und Testdaten. |
| `backend/src/main/java/studio/agent/graph/`, `quality/`, `workflow/` | Knotenregistrierung, Graphprüfung, Beispielvergleiche, Veröffentlichung, Hintergrundausführung und Wiederaufnahme nach einem Signal. |
| `backend/src/main/java/studio/agent/extensions/` | Beispiel für einen fachlichen Knoten: `FilterAssignedNode`. |
| `backend/src/main/java/studio/agent/meeting/` | API des früheren Besprechungsworkflows, Momentaufnahmen und Freigabetransaktionen. |
| `backend/src/main/java/studio/agent/model/` | Strukturierte Modellausgaben und Verbrauchserfassung über die OpenAI-kompatible Schnittstelle von Spring AI. |
| `frontend/src/` | Editor, Zeichenfläche, Knoteneinstellungen, Verlaufsansicht, Beispielauswertung und HTTP-Client. |
| `backend/src/test/`, `frontend/e2e/`, `tests/` | Java-Regressionstests, Workflow-Tests im Browser und Persistenzprüfungen über mehrere Starts der JAR-Datei hinweg. |

H2 speichert seine Dateien standardmäßig unter `data/` im **Arbeitsverzeichnis des Backend-Prozesses**. Starte das Backend aus demselben Verzeichnis, um frühere Durchläufe fortzusetzen. Die Datenbank kann Prompts, Eingaben, Ergebnisse und Momentaufnahmen enthalten. Zugriff und Sicherungen verwaltest du lokal. Bei den aktuellen Workflows erfordern echte Modellaufrufe innerhalb eines vollständigen Durchlaufs deine Zustimmung zur Speicherung der Inhalte für 24 Stunden. Für Beispieldurchläufe, ältere Verlaufsdaten und Sicherungen gelten eigene Aufbewahrungsregeln.

Für MySQL brauchst du eine leere Datenbank und ein Konto mit Berechtigungen ausschließlich für diese Datenbank. Führe die folgenden Befehle vom Stammverzeichnis aus, um die MySQL-Variante zu bauen und zu starten.

```sh
cd backend
mvn -Pmysql verify
java -jar target/agent-studio-backend-0.1.0-SNAPSHOT-mysql.jar
```

Setze vor dem Start der JAR-Datei `STUDIO_DB_URL` auf die JDBC-URL und konfiguriere `STUDIO_DB_USERNAME` sowie `STUDIO_DB_PASSWORD`. Der Standardbuild mit `mvn verify` verwendet H2. Das MySQL-Profil enthält zusätzlich den MySQL-Treiber und das MySQL-Modul für Flyway.

## Ein Modell anbinden

Um Aufgaben mit einem Modell zu extrahieren, aktiviere Modellaufrufe und hinterlege die Zugangsdaten des Anbieters als **Umgebungsvariablen des Backends**. Starte den Dienst neu und wähle im Editor **真实模型** (Modellaufruf). Feste Beispiele prüfen Felder und Verzweigungen. Die Qualität eines Prompts beurteilst du mit dem angeschlossenen Modell.

| Variable | Bedeutung |
| --- | --- |
| `STUDIO_LIVE_ENABLED` | Mit `true` aktivierst du Modellaufrufe. Standardmäßig sind sie ausgeschaltet. |
| `STUDIO_MODEL_API_KEY`, `STUDIO_MODEL_NAME` | Dein API-Schlüssel und der Modellname beim Anbieter. |
| `STUDIO_MODEL_BASE_URL` | Standardwert ist `https://api.openai.com/v1`. Für einen anderen kompatiblen Dienst gibst du dessen API-Basis-URL an. Ein lokales Modell kann eine HTTP-Adresse auf der Loopback-Schnittstelle verwenden. |
| `STUDIO_MODEL_TOKEN_FIELD` | Standardwert ist `max_completion_tokens`. Für Anbieter, die den älteren Parameternamen verlangen, verwende `max_tokens`. |
| `STUDIO_LIVE_DAILY_LIMIT` | Höchstzahl der Modellaufrufe in einem gleitenden 24-Stunden-Zeitraum. Das Standardlimit von `5` gilt gemeinsam für aktuelle Workflows, den früheren Workflow und einzelne Knotentests mit Modellzugriff. |

Bei Modellaufrufen wird der zugeordnete Text an den konfigurierten Anbieter gesendet. Dabei können Kosten entstehen. Bewahre Schlüssel auf dem Server auf und greife über die Loopback-Adresse auf Agent Studio zu. Ein Mehrbenutzerbetrieb oder die Bereitstellung im Internet erfordert ein eigenes Konzept für Authentifizierung, Datenzuordnung und Zugriffskontrolle.

## Bauen und testen

Unter `backend/` formatiert `mvn spotless:apply` den Java-Code. `mvn verify` prüft die Formatierung, führt Regressionstests aus und baut die JAR-Datei. Unter `frontend/` formatiert `npm run format` den Code. `npm run format:check` prüft die Formatierung und `npm run build` prüft die Typen und baut die Anwendung.

Führe die folgenden Befehle im Stammverzeichnis aus, um den Build aus dem Quellcode zu prüfen.

```sh
mvn -f backend/pom.xml verify
python tests/check_persistence.py
cd frontend
npm ci
npm run build
npx playwright install chromium
npm run test:e2e
```

Die Browsertests starten eigene Dienste und eine In-Memory-Datenbank. Sie verwenden die lokalen Ports 18181 und 14173. Der MySQL-Persistenztest benötigt Docker. Führe im Stammverzeichnis zuerst `mvn -f backend/pom.xml -Pmysql verify` und danach `python tests/check_persistence.py --mysql` aus. Das Skript erstellt einen temporären MySQL-8.4-Container, startet dieselbe Backend-JAR zweimal und prüft, ob Entwürfe, Momentaufnahmen und Freigaben den Neustart überdauern.

Die SQL-Formatregeln stehen in `.sqlfluff`. Mit `sqlfluff lint backend/src/main/resources/db/migration` prüfst du die Migrationen. Die CI verwendet SQLFluff 3.4.2.

## Lizenz

Der projekteigene Code der Agent Studio Community Edition steht unter der [Apache License 2.0](LICENSE). Für Drittkomponenten gelten die jeweiligen Lizenzen und Urhebervermerke. [Hinweise zu Drittkomponenten](THIRD_PARTY_NOTICES.md)
