# infra — CHANGELOG

안정성과 성능을 결정하는 **순수 플러밍**.
Key files: `AgentHttpServer`, `Pathfinder`, `PathFollower`, `RuntimeManager`, `AsyncAction`, `ActiveActionManager`, `AgentLogger`, `BridgeClient`, `ChatMonitor`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 인프라.

- `AgentHttpServer`: Per-agent 라우팅 코어 (`/agent/{name}/*`), `GET /agents/list`, `GET/POST /agent/{name}/persona`, `POST /agents/delete`
- `RuntimeManager`: 싱글 프로세스 → AgentContext 기반, `AGENT_NAME`, `AGENT_PERSONA_CONTENT`, `AGENT_TOOLS` env var 전달
- `BridgeClient`: `get()` 메서드 추가
- `ClientSetup`: G키 (에이전트 관리) 바인딩 추가

---

## v0.2.5 — 2026-03-12

메모리 엔드포인트 + GUI 통신.

- `AgentHttpServer`: `/memory/*` 엔드포인트 5개 추가
- `AgentMod`: 서버 시작 시 `MemoryManager.load()`, 종료 시 `save()`
- `BridgeClient` 신규: GUI → HTTP bridge 비동기 통신

---

## v0.2.4 — 2026-03-12

세션 관리.

- `RuntimeManager`: 서버 시작 시 UUID `sessionId` 생성, `AGENT_SESSION_ID` + `AGENT_IS_RESUME` 환경변수 전달
- `RuntimeManager.resetSession()`: 서버 종료 시 세션 초기화

---

## v0.2.3 — 2026-03-12

채팅 줄바꿈 수정.

- `RuntimeManager.relayToChat()`: `\n` 줄바꿈 처리 + 줄별 전송 (최대 20줄), 300자 truncate 제거 (I-009)

---

## v0.2.1 — 2026-03-12

채팅 word-wrap.

- `ChatMonitor`: 60자 기준 줄바꿈 + word-wrap (채팅 잘림 수정)

---

## v0.2.0 — 2026-03-11

동적 타임아웃 + 패스파인딩 개선.

- `AsyncAction.getTimeoutMs()`: 액션별 동적 타임아웃 (하드코딩 60초 제거)
- `PathFollower.getCurrentTarget()`: 현재 웨이포인트 조회
- `AgentHttpServer`: 동적 타임아웃 적용

---

## v0.1.0 — 2026-03-11

초기 구현.

- HTTP 브릿지 (localhost:0, 자동 포트)
- A* 패스파인딩 + 틱 기반 이동
- AsyncAction 인프라 (ActiveActionManager, 60초 하드코딩 타임아웃)
- Claude Agent SDK 런타임 + MCP 서버
- 모니터링 (채팅, 터미널 연동)
- 구조화 JSONL 로거
