# Browser Agent — repo guide

## Stack
- **Java 21 + Spring Boot 3.3.0** (Web + WebSocket) with **Maven**
- **LangChain4j 0.36.0** — LLM orchestration (tool-calling agent pattern via `AiServices`)
- **Playwright 1.49.0** — browser automation (Chromium)
- Single module, no tests, no CI, no mvnw wrapper

## Commands
```bash
mvn spring-boot:run              # Start dev server on :8080
mvn compile                      # Compile only
mvn test                         # No tests exist — will pass (empty)
mvn clean package -DskipTests    # Build jar
```

## Key architecture
| Layer | Entrypoint | Notes |
|---|---|---|
| App | `BrowserAgentApplication.java` | `@SpringBootApplication` |
| Config | `config/LangChain4jConfig.java` | LLM provider: **Anthropic → Azure → OpenAI** (first configured wins) |
| Config | `config/WebSocketConfig.java` | STOMP over SockJS at `/ws` |
| API | `web/AgentController.java` | `POST /api/run`, `GET /api/report`, `GET /api/report/download`, `GET /api/status`, `GET/POST /api/tests`, `DELETE /api/tests/{id}`, `POST /api/tests/{id}/run` |
| Agent | `agent/BrowserAgentService.java` | `@Async` — one task at a time (AtomicBoolean mutex on controller) |
| Tools | `agent/BrowserTools.java` | Every `@Tool` method becomes an LLM-callable function |
| Report | `agent/ReportService.java` | Writes HTML to `reports/` dir (screenshots base64-embedded) |
| Tests | `agent/TestService.java` | Saves/loads test cases as JSON in `tests/tests.json` |

## LLM provider configuration
Set exactly **one** of these env vars (checked in this order):

```
ANTHROPIC_API_KEY=sk-...              # → Claude Sonnet 4 (default model)
AZURE_OPENAI_ENDPOINT=https://...     # + AZURE_OPENAI_API_KEY + AZURE_OPENAI_DEPLOYMENT
OPENAI_API_KEY=sk-...                 # → GPT-4o (fallback)
```

Model names are configurable via `application.properties` (`agent.model`, `openai.model`, etc.)

## Important quirks
- **Only one task at a time** — `POST /api/run` returns `409` if already running
- **Reports land in `reports/`** (git-ignored, created on first run)
- **Headless by default** — set `browser.headless=false` for visible browser
- **No tests exist** — repo has no `src/test/` directory at all
- **No mvnw wrapper** — system `mvn` or IDE required
- **WebSocket topics**: `/topic/logs` (live stream), `/topic/done` (task end), `/topic/report-ready` (report filename)
- Frontend is a single static `index.html` consuming the WebSocket + REST API

## Test case management
- **LLM tools**: `saveTestCase(name, task)` and `listTestCases()` are available as `@Tool` methods in `BrowserTools`
- **API**: `POST /api/tests` (create), `GET /api/tests` (list), `DELETE /api/tests/{id}` (delete), `POST /api/tests/{id}/run` (execute from saved)
- **Storage**: JSON file at `tests/tests.json` (auto-created on first save)
- **Frontend**: "Save as Test" button next to the Run button; saved tests listed below with Run/Delete controls

## Agent report requirements
- **Always call `takeScreenshot()`** after each major step (navigation, search, form submit, final state) — screenshots are base64-embedded in the HTML report
- **Always call `completeTask()`** with a clear, detailed summary when the task is done — this triggers report generation
- **On failure**, still call `completeTask()` with the failure details and root cause analysis; the Java error handler generates a partial report automatically for unhandled exceptions, but an explicit `completeTask()` with analysis produces richer output
