# Agent TUI — 기획 스펙

## 1. 개요

마인크래프트 밖에서 에이전트를 모니터링하고 제어할 수 있는 Terminal User Interface.

### 왜 TUI인가

현재 에이전트를 다루는 인터페이스는 마인크래프트 채팅(`/agent @alex ...`)과 인게임 GUI(`G`키, `M`키)뿐이다. agent-runtime은 UI가 없는 백그라운드 프로세스로, Java RuntimeManager가 띄우고 Claude SDK가 자율 실행한 뒤 종료된다. 마인크래프트 밖에서 에이전트의 사고/행동을 실시간으로 관찰하고 개입할 수 있는 인터페이스가 필요하다.

### CLI vs TUI 결정

Claude Code는 interactive CLI (ink 기반)이지 TUI가 아니다. 고정 레이아웃이나 패널 분할이 없고 프롬프트→스트리밍 출력→프롬프트 루프가 반복되는 구조.

agent-tui는 **좌/우 패널 분할**이 필요하다 — 왼쪽에 에이전트 목록, 오른쪽에 선택된 에이전트의 세션 뷰. 이건 화면 레이아웃 제어가 필요하므로 TUI가 맞다. ink는 좌우 분할이 약하고, **blessed** (ncurses 스타일)가 이 레이아웃에 적합하다.

### 실행 환경

terminal-mod 안이든 일반 터미널(PowerShell, Windows Terminal)이든 차이 없다. terminal-mod가 일반 터미널과 다를 게 별로 없기 때문. bridge-server.json에서 포트를 읽어 자동 연결.

---

## 2. 레이아웃

```
┌──────────────┬─────────────────────────────────────────────────┐
│ [AM] Manager │  [사고] 철광석을 찾는 중...                       │
│  ● running   │  [도구] get_observation                          │
│              │  [결과] ▶ (펼쳐서 보기)                           │
│ ─────────── │  [도구] move_to(3, 63, 0)                        │
│ alex         │  [결과] ▶ (펼쳐서 보기)                           │
│  ● running   │  [도구] mine_block(3, 63, 0)                     │
│ zim          │  [결과] ▶ (펼쳐서 보기)                           │
│  ○ spawned   │  [텍스트] 철광석 3개 채굴 완료                     │
│ steve        │                                                   │
│  · offline   │                                                   │
│              │                                                   │
│              │                                                   │
│              ├─────────────────────────────────────────────────│
│              │ > 남쪽 동굴에서 다이아도 찾아봐                     │
└──────────────┴─────────────────────────────────────────────────┘
```

### Left Pane — 에이전트 리스트

- **Agent Manager 최상단 고정**. AM도 결국 에이전트다. 마인크래프트 세상 밖의 일들(스케줄, 에이전트 조율)을 관리할 뿐이지 UI 구조에서 특별 취급할 이유 없다.
- 그 아래 에이전트 목록: 이름 + 상태 표시
  - `●` + 깜빡임: 활동 중 (runtime 실행 중, 이벤트 발생 중)
  - `●`: spawn 되어 있음
  - `○`: spawn 되어 있으나 runtime 미실행
  - `·`: spawn 안 됨 (디스크에만 존재)
- spawn 안 된 에이전트도 표시한다. `.agent/agents/` 디렉토리 스캔 기반. 이미 `GET /agents/list`가 디스크 에이전트도 반환하고 있다.
- 선택: `↑`/`↓` 방향키
- 포커스 전환: `Enter` 또는 `→`로 right pane 입력 모드, `Esc`로 left pane 복귀

### Right Pane — 세션 뷰

Claude Code 스타일 — 스크롤되는 로그 스트림 + 하단 입력 라인.

**로그 표시 규칙:**
- `thought` — 그대로 나열
- `tool_call` — 그대로 나열 (도구 이름 + 파라미터)
- `text` — 그대로 나열 (Claude 최종 응답)
- `tool_result` — **접힌 상태**로 표시. 사용자가 선택적으로 펼쳐서 볼 수 있다.
  - 이유: tool_result는 보통 길고 (observation JSON 등), 매번 전부 보여주면 로그 흐름을 방해한다. 필요할 때만 펼쳐보는 게 맞다.

**에이전트별 독립 로그 버퍼:**
- 각 에이전트(AM 포함)는 자신만의 로그 버퍼를 가진다. alex 보다가 zim으로 전환해도 alex 로그는 보존되고, 다시 돌아오면 그대로 보인다.
- Claude Code에서 개발 세션 탭과 코드 리뷰 탭을 오가는 것과 같은 개념.

**입력:**
- `/spawn` — 선택된 에이전트 spawn
- `/despawn` — despawn
- `/stop` — runtime 중지
- 그 외 텍스트 — 선택된 에이전트에게 메시지 전달 (= `/agent @name 메시지`와 동일)
- AM 선택 시에도 동일 — 텍스트 입력은 AM에게 메시지 전달 (= `/am 메시지`와 동일)

---

## 3. 이벤트 시스템

### 왜 EventBus인가

현재 에이전트 이벤트의 흐름:

```
agent-runtime (TS) → stdout → RuntimeManager (Java)
                                      ↓
                               relayToChat()
                                      ↓
                        TerminalIntegration.sendLog(type, message)
                                      ↓
                              MonitorLogBuffer.add()          ← 링 버퍼 (최근 500개)
                                      ↓
                        ChatMonitor (10틱마다 폴링)            ← 마크 채팅에 포맷팅 출력
                                      ↓ (별도 경로)
                              AgentLogger                     → JSONL 파일
```

이미 EventBus의 원시적인 형태가 수동으로 구현되어 있다:
- `MonitorLogBuffer` = 이벤트 버퍼 (링 버퍼, 500개)
- `ChatMonitor` = 폴링 방식 구독자 (10틱 = 0.5초마다)
- `TerminalIntegration` = 라우터 (terminal-mod 유무에 따라 분기, 현재는 MonitorLogBuffer만 사용)
- `AgentLogger` = 또 다른 구독자 (JSONL 기록)

문제: 소비자를 추가(TUI)하려면 이 수동 배선을 또 복제해야 한다. 각 소비자가 독립적으로 RuntimeManager 출력을 처리하고 있어서 구조가 분산되어 있다.

**EventBus**를 도입하면:

```
agent-runtime (TS) → stdout → RuntimeManager (Java) → EventBus
Java 액션/라이프사이클/스케줄 ────────────────────────→ EventBus
                                                          ↓
                                                   ┌──────┼──────┐
                                                   ↓      ↓      ↓
                                                 채팅   SSE    JSONL
                                               (필터)  (필터)  (전부)
```

- **이벤트 생산**은 Java에서. 이유: Java가 모든 이벤트의 합류 지점이다. 런타임 stdout도 Java(RuntimeManager)가 읽고, 액션/스케줄/라이프사이클도 Java에서 발생한다. TypeScript는 이벤트를 발생시키기만 하고 누가 어떻게 소비하는지 모른다.
- **필터링은 각 소비자가 결정**. 마크 채팅은 최종 응답(`text`, `error`)만, SSE는 전부 보내고 TUI가 UI 포매팅, JSONL은 전부 기록.

### 리팩토링 대상

기존 monitor 패키지의 수동 이벤트 배선이 EventBus로 통합된다:

| 기존 | 역할 | EventBus 전환 후 |
|------|------|-----------------|
| `RuntimeManager.relayToChat()` | stdout 파싱 → 이벤트 생성 | `EventBus.publish()` 호출로 변경 (이벤트 생산자) |
| `TerminalIntegration.sendLog()` | MonitorLogBuffer로 라우팅 | 제거 — EventBus가 라우팅 담당 |
| `MonitorLogBuffer` | 링 버퍼 (500개) | 제거 — EventBus 내부 버퍼로 대체 |
| `ChatMonitor` | 10틱 폴링 → 마크 채팅 출력 | `ChatSubscriber`로 전환 (EventBus 구독자) |
| `AgentLogger` | JSONL 기록 | `LogSubscriber`로 전환 (EventBus 구독자, 선택적 활성화) |
| (신규) | TUI로 push | `SSESubscriber` (EventBus 구독자) |

`TerminalIntegration`의 terminal-mod 분기 로직은 TUI가 대체하므로 삭제 가능.

### 이벤트 타입

**런타임 이벤트** (agent-runtime stdout → Java가 파싱)
- `THOUGHT` — Claude가 생각한 내용
- `TOOL_CALL` — MCP 도구 호출 (도구 이름, 파라미터)
- `TOOL_RESULT` — 도구 실행 결과
- `TEXT` — Claude 최종 응답
- `ERROR` — 런타임 에러

**라이프사이클 이벤트** (Java에서 직접 발생)
- `SPAWNED` / `DESPAWNED`
- `RUNTIME_STARTED` / `RUNTIME_STOPPED`
- `PAUSED` / `RESUMED`

**스케줄 이벤트** (ScheduleManager에서 발생)
- `SCHEDULE_TRIGGERED` — 스케줄 발동
- `OBSERVER_FIRED` — 옵저버 감지

**액션 이벤트** (ActionManager에서 발생)
- `ACTION_STARTED` / `ACTION_COMPLETED` / `ACTION_FAILED`

### 이벤트 데이터 모델

```java
AgentEvent {
    long timestamp;
    String agentName;       // "alex", "manager" 등
    EventType type;         // 위 enum
    JsonObject data;        // 타입별 가변 데이터
}
```

data의 내용은 타입에 따라 달라진다:
- TOOL_CALL: `{ "tool": "move_to", "params": { "x": 3, "y": 63, "z": 0 } }`
- SPAWNED: `{ "position": { "x": 10, "y": 64, "z": -5 } }`
- ACTION_COMPLETED: `{ "action": "mine_block", "result": "success" }`

---

## 4. 로그 스트리밍 — SSE (Server-Sent Events)

### 대안 검토

| 방식 | 설명 | 실시간성 | 확장성 | 네트워크 효율 | 원격 대응 |
|------|------|---------|--------|-------------|----------|
| A. 파일 tail | TUI가 JSONL 파일을 tail -f처럼 감시 | 좋음 | 파일 포맷 종속 | N/A | 불가 (로컬 파일) |
| B. HTTP 폴링 | TUI가 0.5초마다 새 로그 요청 | 폴링 간격만큼 지연 | 좋음 | 빈 응답 낭비 | 가능 |
| C. SSE | Java가 연결 유지하며 이벤트 발생 시 push | 즉시 | 이벤트 타입별 분리 가능 | 변화 있을 때만 전송 | 가능 |

### 결정: C (SSE)

구현 난이도는 고려하지 않는다 — 품질 기준으로만 평가.

SSE가 최적인 이유:
- **로그 모니터링은 본질적으로 이벤트 스트림**이다. "새 로그 있어?" 하고 물어보는 게 아니라, 생기면 바로 오는 게 자연스럽다.
- **한 스트림에 이벤트 타입별 분리 가능** — 나중에 새 이벤트 타입 추가가 쉽다.
- **네트워크 효율** — 이벤트 없으면 트래픽 없음. 폴링은 빈 응답이 계속 발생.
- **디버깅** — curl이나 브라우저 EventSource로도 테스트 가능.
- **원격 대응** — HTTP 기반이므로 로컬/원격 동일 (파일 tail은 로컬에서만 가능. 단, B/C 모두 원격 시 Java HttpServer 바인딩 주소 변경 필요).

### SSE 연결 구조

**TUI당 SSE 연결 하나로 모든 에이전트 이벤트 수신.**

각 에이전트별로 SSE를 따로 연결하지 않는다. 이유:
- 에이전트 전환할 때마다 연결 끊고 다시 연결하면 이벤트 유실 가능
- 비활성 에이전트의 이벤트도 받아야 left pane 활동 표시(깜빡임)가 가능
- TUI가 에이전트별 버퍼에 분배하고, 현재 선택된 에이전트의 버퍼만 right pane에 표시

엔드포인트: `GET /events/stream` (전체 이벤트 스트림)

---

## 5. 히스토리 — SDK Transcript 활용

### 문제

TUI를 에이전트 활동 이후에 켤 수 있다. 마크에서 agent spawn하고 명령을 주고받은 뒤 TUI를 켜면, 이전 대화를 볼 수 있어야 한다.

### 중복 저장 문제

현재 세션 히스토리가 저장되는 곳:
1. **SDK transcript** — Claude SDK의 `persistSession=true`가 자동 저장. 세션 resume에 사용. 전체 대화(프롬프트, 응답, 도구 호출/결과) 포함.
2. **JSONL** — AgentLogger가 기록. 디버깅/분석용.
3. **마크 채팅** — 휘발성 (스크롤 지나가면 끝).

SDK transcript와 JSONL은 내용이 거의 같다. 목적이 다를 뿐:
- SDK transcript = 기계용 (Claude가 이전 대화 기억)
- JSONL = 사람용 (디버깅/분석)

### 결정: 히스토리 = SDK transcript

TUI의 히스토리 보존 단위는 **SDK transcript 하나 = 하나의 세션**이다. 별도 링 버퍼나 JSONL 기반 히스토리 재구성이 필요 없다.

### 어떤 transcript를 불러올 것인가

문제: TUI가 실행되기 이전에 agent가 spawn한 건지, 어제 마인크래프트 할 때 쓴 건지 구분해야 한다. 이에 따라 transcript 불러오기 전략이 달라진다.

해결: **Java가 현재 sessionId를 알고 있다.**

```
서버 시작 → RuntimeManager가 sessionId (UUID) 생성
서버 종료 → sessionId 리셋
```

TUI 접속 흐름:

```
TUI: GET /session/info
Java: {
  sessionId: "abc-123",
  agents: [
    { name: "alex", spawned: true, hasLaunched: true },   ← 이 세션에서 활동함 → transcript 로드
    { name: "zim", spawned: true, hasLaunched: false },    ← spawn만 됨 → transcript 없음
    { name: "steve", spawned: false }                       ← 안 떠있음 → 히스토리 없음
  ]
}
```

sessionId가 transcript 불러오기의 키. 어제 세션은 sessionId가 다르므로 자연스럽게 걸러진다.

### JSONL(AgentLogger)의 역할 재검토

#### transcript vs JSONL — 기록하는 것이 다르다

| | SDK transcript | AgentLogger JSONL |
|---|---|---|
| 관점 | **Claude 관점** — 뭘 생각하고 뭘 시켰는지 | **마인크래프트 관점** — 실제로 뭐가 일어났는지 |
| tool_call | `mine_area({ x1:0, z1:0, x2:5, z2:5 })` | (없음 — 이건 Claude 쪽 기록) |
| tool_result | `{ ok: true, mined: 36, items: [...] }` (요약) | (없음) |
| action 실행 | (없음 — Java에서 발생) | `action: mine_area, params, result, duration_ms` |
| substep | (없음 — Claude는 요약만 받음) | 블록 단위 상세: `(2,64,0) ok=false, error="no_path"` |
| duration | 타임스탬프만 (명시적 소요시간 없음) | `duration_ms: 3420` |
| 라이프사이클 | (없음 — 대화 바깥 이벤트) | `spawn`, `despawn`, `session_start/end` |

핵심 차이: `mine_area`가 256블록을 처리할 때, transcript에는 요약 결과만 돌아가고, JSONL에는 블록 하나하나의 실행 상세(substep)가 기록된다. 이것은 transcript에 없는 마인크래프트 쪽 고유 데이터다.

#### 결론

JSONL(AgentLogger)은 **제거 대상이 아니라 유지 대상**이다. transcript과 중복이 아니라 상호 보완:
- transcript = "Claude가 뭘 생각하고 뭘 시켰는지" (기계용, 세션 resume)
- JSONL = "마인크래프트에서 실제로 뭐가 일어났는지" (사람용, 디버깅/분석)

EventBus 구독자(`LogSubscriber`)로 리팩토링하되, 기록 자체는 유지한다. SDK transcript은 건드리지 않는다 (세션 resume 전용).

---

## 6. 기술 스택

| 항목 | 선택 | 이유 |
|------|------|------|
| 패키지 | `agent-tui/` (별도) | agent-runtime과 독립 실행, 관심사 분리 |
| 언어 | Node.js + TypeScript | agent-runtime과 스택 통일, 빠른 개발 |
| TUI 프레임워크 | blessed | ncurses 스타일, 패널 분할/스크롤 잘 지원. ink는 React 기반이라 좌우 분할이 약함 |
| 연결 | bridge-server.json → 포트 자동 감지 | 기존 패턴 그대로 |

---

## 7. Java 측 변경 사항

### 신규
- `AgentEvent.java` — 이벤트 데이터 모델 (timestamp, agentName, type, data)
- `EventType.java` — 이벤트 타입 enum
- `EventBus.java` — 중앙 이벤트 허브 (publish/subscribe, 스레드 안전)
- `ChatSubscriber.java` — 마크 채팅 출력 구독자 (기존 ChatMonitor 역할 흡수)
- `SSESubscriber.java` — SSE 스트림 구독자 (TUI로 push)
- `LogSubscriber.java` — JSONL 기록 구독자 (기존 AgentLogger 역할, 선택적 활성화)
- SSE 엔드포인트 (`GET /events/stream`) — AgentHttpServer에 추가
- 세션 정보 엔드포인트 (`GET /session/info`) — AgentHttpServer에 추가

### 리팩토링
- `RuntimeManager.relayToChat()` → stdout 파싱 후 `EventBus.publish()` 호출로 변경 (이벤트 생산자 역할만 남김)
- `AgentManager` — spawn/despawn 시 라이프사이클 이벤트 발행
- `ActiveActionManager` — 액션 시작/완료/실패 시 이벤트 발행
- `ScheduleManager` — 스케줄 트리거/옵저버 감지 시 이벤트 발행

### 제거
- `MonitorLogBuffer.java` — EventBus 내부 버퍼로 대체
- `ChatMonitor.java` — `ChatSubscriber`로 대체
- `TerminalIntegration.java` — EventBus 라우팅 + TUI가 대체

---

## 8. 에러 처리 / Edge Case

설계 시 고려해야 할 케이스 (구현 단계에서 상세 설계):

- 마크 서버 미실행 시 TUI 동작 (연결 실패 → 재연결 시도 + 대기 화면)
- SSE 연결 끊김 (자동 재연결 + 누락 이벤트 catch-up)
- 에이전트 despawn 중 TUI에서 해당 에이전트 선택 상태
- 서버 재시작 시 sessionId 변경 → TUI 자동 감지 + 로그 버퍼 초기화
- 다수 에이전트 동시 활동 시 이벤트 볼륨
- transcript 파일 접근 실패 (SDK 내부 포맷 변경 등)
- TUI 다수 인스턴스 동시 접속
