# tpm-proxy — Spezifikation

## 1. Zweck

`tpm-proxy` ist ein lokaler Vermittler (Reverse-Proxy) zwischen
Coding-CLI-Tools (**opencode**, **Claude Code CLI**) und **Langdock**
als Anthropic-kompatiblem LLM-Gateway. Beide Tools unterstützen einen
konfigurierbaren Upstream (`ANTHROPIC_BASE_URL` /
`ANTHROPIC_AUTH_TOKEN` bzw. das opencode-Äquivalent) — statt direkt
gegen Langdock zu sprechen, zeigen sie auf `tpm-proxy`, der:

1. **Tokens zählt**, die durch ihn hindurchfließen (Input + Output,
   pro Request und kumulativ über ein rollierendes Zeitfenster), und
2. die **Geschwindigkeit** (effektives Tokens-Per-Minute-Budget, mit
   dem an Langdock weitergeleitet wird) **zur Laufzeit einstellbar**
   macht — ohne Neustart des Prozesses.

**Hintergrund/Motivation:** Langdocks Anthropic-kompatibler Endpunkt
hat standardmäßig ein Limit von **500 RPM und 60.000 TPM**, wobei das
TPM-Limit **workspace-weit pro Modell geteilt** wird (nicht pro
API-Key). Laufen mehrere Tools/Prozesse ungebremst gegen denselben
Workspace, kann ein einzelnes Tool (z.B. ein CLI-Agent mit vielen
parallelen Requests) das gemeinsame Kontingent aufbrauchen und allen
anderen Nutzern 429s bescheren. `tpm-proxy` gibt der lokalen
Entwicklerin/dem Entwickler die Kontrolle, wie viel vom geteilten
Budget die eigenen Tools maximal beanspruchen — und erlaubt, dieses
Tempo je nach Situation hoch- oder runterzudrehen.

**Streaming wird von Anfang an (v1) unterstützt**, sofern Langdocks
Anthropic-kompatibler Endpunkt SSE-Streaming durchreicht (zu
verifizieren beim Implementierungsstart, siehe Abschnitt 10). Sowohl
`"stream": true` als auch nicht-streamende Requests laufen durch
dieselbe Budget-Logik (Abschnitt 5).

## 2. Nicht-Ziele (v1)

- Keine getrennten Budgets pro Tool (opencode vs. Claude Code CLI)
  oder pro Nutzer — **ein** gemeinsames, lokal konfiguriertes
  TPM-Budget für allen Traffic, der durch diese Proxy-Instanz läuft.
- Kein RPM- oder TPD-Limit (nur TPM). Langdocks eigenes RPM-Limit
  (500 RPM) wird nicht separat durchgesetzt, nur beobachtet
  (Passthrough der `429`-Antworten, falls Langdock selbst blockt).
- Keine Persistenz des Zählerstands über Neustarts hinweg (in-memory
  reicht für v1).
- Kein Caching, keine Request-Transformation der Message-Payload
  (reines Pass-through von Body/Headern, bis auf Auth).

## 3. Architektur

- **Sprache/Runtime:** Java (JDK 21+).
- **HTTP-Server:** eingebetteter Server (z.B.
  `com.sun.net.httpserver.HttpServer` oder ein Leichtgewicht-Framework
  wie Javalin) — Entscheidung bei Implementierungsbeginn.
- **Upstream-Client:** `java.net.http.HttpClient` (JDK-Bordmittel) für
  die Weiterleitung an Langdock. Kein Anthropic-SDK nötig, da der
  Proxy als transparenter HTTP-Layer arbeitet. Für Streaming-Requests
  wird die Antwort **nicht gepuffert**, sondern per
  `BodyHandlers.ofLines()`/`InputStream` zeilenweise an den Client
  weitergereicht (SSE-Passthrough), während ein einfacher
  SSE-Parser mitliest, um `message_start`/`message_delta`-Events für
  die Token-Buchhaltung zu erkennen (siehe 5.1).
- **State:** in-memory Sliding-Window-Zähler für verbrauchte Tokens
  pro Minute, **laufzeit-veränderbares** Limit (siehe 5.2/5.4).
  Thread-safe, da Requests von mehreren Tools parallel eintreffen
  können.

```
opencode ─┐
          ├─→ tpm-proxy (:8080, konfigurierbar)
Claude    │        │
Code CLI ─┘        ├─ 1. Auth/Validierung (lokaler Client-Token, optional)
                    ├─ 2. Preflight-Token-Schätzung
                    ├─ 3. Budget-Check (Sliding Window, Limit live änderbar)
                    │     ├─ Budget frei      → weiterleiten
                    │     └─ Budget erschöpft → 429 (lokal) oder Queue
                    ├─ 4. Forward an Langdock (Anthropic-kompatibel)
                    └─ 5. Response durchreichen + usage-Felder ins
                          Sliding Window einbuchen
```

## 4. Konfiguration

Über Umgebungsvariablen (Startwerte) plus Laufzeit-Override (siehe 5.4):

| Variable | Pflicht | Beschreibung |
|---|---|---|
| `LANGDOCK_API_KEY` | ja | Langdock API-Key. Wird beim Forward als `Authorization: Bearer <key>` gesetzt (Langdock nutzt Bearer-Auth, **kein** `x-api-key` wie die native Anthropic API). |
| `LANGDOCK_BASE_URL` | nein (Default `https://api.langdock.com/anthropic/eu`) | Ziel-Endpunkt. `/anthropic/eu` oder `/anthropic/us` je nach Workspace-Region; bei Dedicated-Deployment stattdessen `https://<deployment-domain>/anthropic`. |
| `TPM_LIMIT` | ja | Start-Wert für das durchgesetzte TPM-Budget (Input + Output) pro rollierendem 60s-Fenster. Zur Laufzeit änderbar (5.4). Sollte spürbar unter Langdocks geteiltem 60.000-TPM-Workspace-Limit liegen, um Headroom für andere Nutzer/Tools zu lassen. |
| `PROXY_PORT` | nein (Default `8080`) | Port, auf dem der Proxy lauscht. |
| `PROXY_CLIENT_TOKEN` | nein | Falls gesetzt: Clients (opencode, Claude Code CLI) müssen diesen Token mitsenden, um den Proxy zu nutzen. |
| `QUEUE_TIMEOUT_MS` | nein (Default `30000`) | Wie lange ein Request maximal auf freies Budget wartet, bevor er mit 429 abgelehnt wird. |

Der Proxy validiert beim Start, dass `LANGDOCK_API_KEY` und
`TPM_LIMIT` gesetzt sind, und bricht sonst mit klarer Fehlermeldung ab.

**CLI-seitige Einrichtung** (Beispiel Claude Code, analog für opencode
mit dessen jeweiligen Env-Var-Namen):

```
ANTHROPIC_BASE_URL=http://localhost:8080
ANTHROPIC_AUTH_TOKEN=<PROXY_CLIENT_TOKEN, falls gesetzt>
```

## 5. TPM-Durchsetzung

### 5.1 Preflight-Schätzung

Vor dem Forward wird die Token-Zahl des eingehenden Requests
geschätzt:

- **Input-Tokens:** lokale Zeichen/4-Heuristik. Langdocks
  `/anthropic/eu`-Endpunkt unterstützt `/v1/messages/count_tokens`
  **nachweislich nicht** (getestet: `404 Not found`) — der
  ursprünglich geplante Preflight-Call gegen Langdock entfällt daher
  komplett, es gibt nur die Heuristik.
- **Output-Tokens:** nicht vorab bekannt; v1 nutzt `max_tokens` aus
  dem Request-Body als konservative Obergrenze für die
  Preflight-Reservierung.
- Reservierung = `geschätzte input_tokens + request.max_tokens`.

Diese Reservierung wird vorläufig ins Sliding Window gebucht, damit
parallele Requests (z.B. gleichzeitig von opencode und Claude Code
CLI) nicht dasselbe Budget doppelt verplanen.

**Korrektur nach Abschluss des Requests** (Reservierung → Ist-Wert):

- **Nicht-Streaming:** aus `usage.input_tokens` +
  `usage.output_tokens` der fertigen JSON-Response.
- **Streaming (SSE):** der Proxy reicht den Event-Stream live an den
  Client durch (Pass-through, kein Puffern der ganzen Antwort), liest
  dabei aber mit: `input_tokens` kommt aus dem `usage`-Feld des
  `message_start`-Events, der finale `output_tokens`-Wert aus dem
  `usage`-Feld des letzten `message_delta`-Events vor `message_stop`.
  Sobald der Stream endet, wird die Reservierung durch diese Ist-Werte
  ersetzt. Bricht der Stream vorzeitig ab (Verbindungsfehler), wird
  die Reservierung nicht unter den bereits gesendeten Anteil
  reduziert (kein negatives Nachbuchen).

### 5.2 Sliding Window

- Rollierendes 60-Sekunden-Fenster (nicht fixe Minute-Buckets).
- Implementierung: Deque von `(timestamp, tokenCount)`-Einträgen; bei
  jedem Check werden Einträge älter als 60s verworfen und die
  verbleibenden Werte summiert.
- `verfügbares Budget = aktuelles TPM_LIMIT − Summe(aktuelles Fenster)`.

### 5.3 Verhalten bei erschöpftem Budget

- Passt die Reservierung nicht ins aktuelle Budget, wartet der
  Request bis zu `QUEUE_TIMEOUT_MS` lang (Polling/Wakeup, sobald
  ältere Fenstereinträge ablaufen).
- Wird innerhalb des Timeouts kein Budget frei, antwortet der Proxy
  mit `429` im Anthropic-kompatiblen Fehlerformat:
  ```json
  {
    "type": "error",
    "error": {
      "type": "rate_limit_error",
      "message": "tpm-proxy: local TPM budget exhausted, retry later"
    }
  }
  ```
  inklusive `retry-after`-Header.
- Ein echtes `429` von Langdock selbst (z.B. weil das lokale Limit zu
  großzügig gewählt wurde oder andere Nutzer das Workspace-Budget
  ausschöpfen) wird unverändert an den Client durchgereicht.

### 5.4 Laufzeit-einstellbare Geschwindigkeit

Kernanforderung: das TPM-Budget muss **ohne Neustart** verändert
werden können.

- `PUT /internal/limit` mit Body `{"tpmLimit": <int>}` setzt das
  aktive Limit sofort neu; wirkt ab dem nächsten Budget-Check.
- `GET /internal/limit` liefert das aktuell aktive Limit.
- Änderungen werden geloggt (alter Wert → neuer Wert, Zeitstempel).
- v1 hält den Wert nur in-memory (kein Zurückschreiben in
  Konfigurationsdateien); nach einem Neustart gilt wieder der
  `TPM_LIMIT`-Startwert aus der Umgebung.

## 6. Request/Response-Handling

- **Pfad:** Proxy exponiert `POST /v1/messages` (Kernpfad, den
  Claude Code / opencode ansprechen) sowie transparent alle weiteren
  Pfade, die Langdocks Anthropic-kompatibler Endpunkt anbietet
  (Pass-through ohne Budget-Check für Nicht-`/v1/messages`-Pfade,
  z.B. `/v1/models`).
- **Header:** Alle Client-Header außer `x-api-key`/`Authorization`
  werden durchgereicht (z.B. `anthropic-version`, `anthropic-beta`).
  Die Auth gegenüber Langdock wird vom Proxy selbst gesetzt (siehe
  Konfiguration) — Client-seitige Auth-Header werden verworfen/
  ignoriert, außer für die optionale lokale `PROXY_CLIENT_TOKEN`-Prüfung.
- **Body:** unverändert durchgereicht. `"stream": true` wird
  unterstützt (siehe 5.1) — vorausgesetzt, Langdock reicht SSE
  durch (Verifikation ausstehend, Abschnitt 10). Ist das nicht der
  Fall, muss der Proxy dies erkennen und sauber degradieren (z.B.
  intern non-streaming an Langdock anfragen und dem Client einen
  synthetischen Single-Chunk-Stream liefern) — Detailentscheidung bei
  Implementierungsstart.
- **Fehler von Langdock (400/401/403/404/413/429/500/529):**
  unverändert 1:1 an den Client durchgereicht.

## 7. Beobachtbarkeit

- `GET /internal/status`: aktueller Fensterverbrauch, aktives
  `tpmLimit`, verbleibendes Budget, Anzahl aktuell wartender
  Requests, Quelle (falls unterscheidbar) pro Tool.
- Strukturiertes Logging pro Request: Preflight-Schätzung,
  tatsächliche Usage, Wartezeit (falls gequeued), finaler Statuscode.

## 8. Sicherheit

- `tpm-proxy` läuft standardmäßig auf `localhost` und ist nicht für
  Exposition im Netzwerk gedacht (hält den Langdock-Key lokal).
- `LANGDOCK_API_KEY` wird nie geloggt oder in `/internal/status`
  zurückgegeben.

## 9. Spätere Erweiterungen (nicht v1)

- Getrennte Budgets/Kontingente pro Tool (opencode vs. Claude Code CLI)
  oder pro `PROXY_CLIENT_TOKEN`.
- Zusätzliche Limits: RPM-Durchsetzung, TPD.
- Persistenter/verteilter Zähler für Multi-Instanz-Betrieb.

## 10. Offene Fragen

- **Streaming bei Langdock:** Muss vor/bei Implementierungsstart per
  Testrequest verifiziert werden, ob Langdocks Anthropic-kompatibler
  Endpunkt `"stream": true` unterstützt und SSE-Events im gleichen
  Format wie die native Anthropic API liefert. Falls nicht, greift
  der in Abschnitt 6 beschriebene Degradations-Pfad.
- ~~`count_tokens` bei Langdock~~ — **geklärt:** nicht unterstützt
  (`404` bei `/anthropic/eu`, live getestet). Siehe 5.1.
