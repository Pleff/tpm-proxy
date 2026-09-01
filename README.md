# tpm-proxy

Lokaler Reverse-Proxy zwischen Coding-CLI-Tools (opencode, Claude Code
CLI) und [Langdocks](https://langdock.com) Anthropic-kompatiblem
Endpunkt. Setzt zwei lokal konfigurierbare Budgets durch, bevor
Requests weitergeleitet werden — **Tokens-Per-Minute (TPM)** und ein
**Tages-Limit (TPD)** — damit ein einzelnes Tool nicht das geteilte
Workspace-Kontingent bei Langdock aufbraucht und allen anderen
Nutzer:innen 429-Fehler beschert.

Das vollständige Design (Architektur, Sliding-Window-Algorithmus,
offene Fragen) steht in [SPEC.md](SPEC.md). Dieses Dokument ist die
kurze Betriebsanleitung.

## Voraussetzungen

- JDK 21+
- Maven 3.9+
- Ein Langdock-API-Key mit Zugriff auf den Anthropic-kompatiblen
  Endpunkt

## Bauen

```bash
mvn -q package
```

Erzeugt `target/tpm-proxy.jar` (ausführbares Fat-Jar).

## Konfiguration

Alles über Umgebungsvariablen:

| Variable | Pflicht | Default | Beschreibung |
|---|---|---|---|
| `LANGDOCK_API_KEY` | ja | – | Langdock-API-Key. Wird als `Authorization: Bearer <key>` an Langdock geschickt. |
| `TPM_LIMIT` | nein | `40000` | Start-TPM-Budget (Input+Output-Tokens pro rollierendem 60s-Fenster). Zur Laufzeit änderbar (siehe unten). |
| `MAX_TOKENS_PER_DAY` | nein | `1000000` | Start-Tages-Budget (Input+Output-Tokens pro Kalendertag, Reset um lokale Mitternacht — **kein** rollierendes 24h-Fenster). Zur Laufzeit änderbar (siehe unten). |
| `LANGDOCK_BASE_URL` | nein | `https://api.langdock.com/anthropic/eu` | Ziel-Endpunkt. `/anthropic/eu` oder `/anthropic/us` je nach Workspace-Region; bei Dedicated-Deployment `https://<deployment-domain>/anthropic`. |
| `PROXY_PORT` | nein | `8080` | **Port, auf dem der Proxy lokal lauscht.** Frei wählbar, z.B. wenn `8080` bereits belegt ist. |
| `PROXY_CLIENT_TOKEN` | nein | – | Falls gesetzt: Clients müssen diesen Token mitsenden (als `x-api-key`-Header oder `Authorization: Bearer <token>`), um den Proxy zu benutzen. Ohne diese Variable ist die lokale Auth-Prüfung aus. |
| `QUEUE_TIMEOUT_MS` | nein | `30000` | Wie lange ein Request maximal auf freies TPM-Budget wartet, bevor er mit `429` abgelehnt wird. Gilt nicht für das Tages-Budget (das lehnt sofort ab, siehe SPEC.md §5.5). |

Einzig `LANGDOCK_API_KEY` ist Pflicht — fehlt sie, bricht der Proxy
beim Start mit klarer Fehlermeldung ab. `TPM_LIMIT` und
`MAX_TOKENS_PER_DAY` sind optional; ohne sie greifen die Defaults
oben.

## Starten

PowerShell:

```powershell
$env:LANGDOCK_API_KEY = "dein-langdock-key"
$env:TPM_LIMIT = "40000"
$env:PROXY_PORT = "8080"   # optional, siehe oben
java -jar target/tpm-proxy.jar
```

Bash:

```bash
LANGDOCK_API_KEY=dein-langdock-key TPM_LIMIT=40000 java -jar target/tpm-proxy.jar
```

Der Proxy bindet ausschließlich an `localhost` (nicht netzwerkweit
erreichbar) und gibt beim Start immer eine saubere Start-Zeile mit
Versionsnummer, Port, Ziel-Endpunkt und den aktiven Start-Limits aus,
z.B.:

```
tpm-proxy v0.1.0-SNAPSHOT - listening on port 8080, forwarding to https://api.langdock.com/anthropic/eu (TPM limit: 40000, daily limit: 1000000)
tpm-proxy dashboard: http://localhost:8080/
```

## Web-Dashboard

Im Browser öffnen: **http://localhost:8080/** (Port anpassen, falls
`PROXY_PORT` abweicht).

Zeigt live (Refresh alle 2s):

- aktuelles TPM-Limit, Auslastung im 60s-Fenster (= aktuelle Rate),
  verbleibendes Budget, aktive Reservierungen
- aktuelles Tages-Limit, Auslastung seit lokaler Mitternacht
  (Kalendertag-Zähler, kein 24h-Fenster), verbleibendes Tagesbudget
- ein Formular, um TPM-Limit und/oder Tages-Limit **ohne Neustart**
  zu ändern (Felder sind unabhängig — nur ausgefüllte werden gesetzt)
- Lifetime-Statistik (Tokens/Requests seit Prozessstart)
- Details zum letzten Request (Modell, Streaming, Tokens, Dauer)

Das Dashboard ist eine statische Seite, die ausschließlich die unten
dokumentierten `/internal/*`-Endpunkte per `fetch()` aufruft — keine
zusätzliche Server-Logik.

## Endpunkte

| Methode & Pfad | Zweck |
|---|---|
| `POST /v1/messages` | Kernpfad: TPM- und TPD-Budget-Check, Forward an Langdock (streaming oder nicht), Buchhaltung. |
| `GET /internal/status` | JSON-Snapshot: Version, TPM- und TPD-Limit/Auslastung, Lifetime-Stats, letzter Request. |
| `GET /internal/limit` | Aktuelles `tpmLimit` und `dailyLimit` als JSON. |
| `PUT /internal/limit` | Limits ändern, Body `{"tpmLimit": <int>}`, `{"dailyLimit": <int>}` oder beides zusammen. Wirkt sofort, nur in-memory (kein Neustart-Persistenz). |
| `GET /` | Web-Dashboard (siehe oben). |

## Live-Logging

Pro Request erscheint eine Zeile auf stdout, z.B.:

```
tpm-proxy: model=claude-sonnet-5 stream=false tokens=35 (in=10 out=25) duration=842ms | window=35/40000 tpm | day=35/1000000 | lifetime=35 tokens / 1 requests
```

Bei lokal abgelehnten Requests (TPM- oder Tages-Budget erschöpft) eine
`REJECTED`-Zeile mit Angabe, welches Budget betroffen war, sowie
`retryAfter`; bei Fehlern von Langdock eine `upstream_status=...`-Zeile.

## CLI-Integration

### Claude Code CLI

In `~/.claude/settings.json` (oder als Umgebungsvariablen):

```
ANTHROPIC_BASE_URL=http://localhost:8080
ANTHROPIC_AUTH_TOKEN=<PROXY_CLIENT_TOKEN, falls gesetzt>
```

### opencode

In `~/.config/opencode/opencode.json` (global) oder `opencode.json` im
Projektroot:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "langdock-proxy": {
      "npm": "@ai-sdk/anthropic",
      "name": "via Langdock (Proxy)",
      "options": {
        "baseURL": "http://127.0.0.1:8080/v1",
        "apiKey": "not-checked-locally"
      },
      "models": {
        "claude-sonnet-5": {
          "name": "Claude Sonnet 5"
        }
      }
    }
  },
  "model": "langdock-proxy/claude-sonnet-5"
}
```

`apiKey` ist nur relevant, falls `PROXY_CLIENT_TOKEN` beim Proxy
gesetzt ist — dann muss der Wert damit übereinstimmen.

## Bekannte Einschränkungen (v1)

- **Kein `count_tokens`-Preflight:** Langdocks `/anthropic/eu`-Endpunkt
  unterstützt `/v1/messages/count_tokens` nicht (bestätigt: `404`).
  Die Input-Token-Schätzung vor dem Forward nutzt daher eine
  Zeichen/4-Heuristik statt eines exakten Werts.
- **Ein gemeinsames Budget** (TPM und TPD) für allen Traffic durch
  diese Proxy-Instanz — keine getrennten Kontingente pro Tool oder
  Client.
- **Kein RPM-Limit.**
- **Keine Persistenz** über Neustarts hinweg (Zähler und laufzeit-
  geänderte Limits sind in-memory; nach Neustart gelten wieder die
  ENV-Var-Defaults).

Details und Architektur-Hintergrund: [SPEC.md](SPEC.md).
