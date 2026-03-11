# 개발 계획

## Team Agent 구성

개발을 3개 팀으로 나누고, 의존성이 없는 작업은 병렬 진행.

```
Team A: Forge Mod (Java)     ← agent-mod 본체
Team B: Runtime (TypeScript)  ← Agent Runtime + MCP
Team C: Integration           ← A+B 연결, 모니터링, 모드호환
```

---

## Phase 0 — 프로젝트 셋업

**선행 조건**: 없음
**결과물**: 빌드 가능한 빈 프로젝트

| 작업 | 팀 | 내용 |
|---|---|---|
| Forge 모드 스캐폴딩 | A | `agent-mod/build.gradle`, 패키지 구조, `@Mod` 엔트리포인트 |
| settings.gradle 등록 | A | 루트에 `include 'agent-mod'` 추가 |
| Node.js 프로젝트 생성 | B | `agent-runtime/package.json`, tsconfig, Agent SDK 의존성 |
| 빌드 검증 | A+B | `./gradlew :agent-mod:build` + `npm run build` 통과 |

```
A: [Forge 스캐폴딩] ──→ [빌드 검증]
B: [Node.js 셋업]   ──→ [빌드 검증]
```

---

## Phase 1 — 뼈대 (Fake Player + HTTP + SDK 연결)

**목표**: `/agent 앞으로 10블록 이동해` → 실제로 Fake Player가 움직이는 것 확인

### Team A: Forge Mod

| 순서 | 작업 | 설명 |
|---|---|---|
| A1 | Fake Player 소환/제거 | `FakePlayer` 생성, 월드에 추가, `/agent spawn` `/agent despawn` |
| A2 | HTTP Bridge 기본 | `HttpServer` 구동, `POST /spawn`, `POST /despawn`, `GET /status` |
| A3 | 기본 이동 | `move_to(x,y,z)` — 직선 이동 (pathfinding 없이 teleport 수준) |
| A4 | Observation 기본 | `GET /observation` — position, inventory만 반환 |
| A5 | `/agent` 명령어 기본 | 채팅 명령 → Agent Runtime 프로세스로 전달 |

### Team B: Agent Runtime

| 순서 | 작업 | 설명 |
|---|---|---|
| B1 | SDK 연동 PoC | `query()` 호출 → 응답 스트리밍 확인 |
| B2 | MCP 서버 기본 | `move_to`, `get_observation` tool 정의 → agent-mod HTTP 호출 |
| B3 | 프롬프트 설계 | 시스템 프롬프트: Agent 역할, 사용 가능한 tool 설명 |

### 의존성 및 병렬화

```
A: [A1 Fake Player] → [A2 HTTP] → [A3 이동] → [A4 Observation]
                                                       ↓
B: [B1 SDK PoC] → [B2 MCP 서버] ──────────────────→ 통합 테스트
                → [B3 프롬프트]                    (A5 명령어 연결)
```

**Phase 1 완료 기준**: 채팅에 `/agent 앞으로 10블록 가` → Fake Player가 이동 → 채팅에 결과 출력

---

## Phase 2 — 기본 행동

**목표**: Agent가 자율적으로 블록을 캐고, 아이템을 줍고, 장비를 바꿀 수 있음

### Team A: Forge Mod

| 순서 | 작업 | 설명 |
|---|---|---|
| A6 | Pathfinding | A* 구현 또는 바리톤 참고. `move_to`를 실제 경로탐색으로 교체 |
| A7 | 블록 행동 | `mine_block`, `place_block` — 틱 단위 분할 처리 |
| A8 | 아이템 행동 | `pickup_items`, `drop_item`, `equip` |
| A9 | 엔티티 행동 | `attack`, `interact` |
| A10 | Observation 확장 | `nearby_blocks`, `nearby_entities` 추가 |

### Team B: Agent Runtime

| 순서 | 작업 | 설명 |
|---|---|---|
| B4 | MCP tool 확장 | A6~A9의 모든 Action을 MCP tool로 등록 |
| B5 | 작업 요약 로직 | Observation의 `task_summary` 생성/갱신 로직 |

### 의존성

```
A: [A6 Pathfinding] → [A7 블록] → [A8 아이템] → [A9 엔티티] → [A10 Observation]
                                                                      ↓
B: ──────────────── [B4 MCP 확장 (A의 진행에 맞춰)] ──────────────→ 통합 테스트
                    [B5 작업 요약] ────────────────────────────────→
```

**Phase 2 완료 기준**: `/agent 철광석 10개 캐서 가져와` → 탐색 → 이동 → 채굴 → 줍기 → 복귀

---

## Phase 3 — GUI + 메모리

**목표**: 화로/상자/제작대 사용 가능, 세션 간 기억 유지

### Team A: Forge Mod

| 순서 | 작업 | 설명 |
|---|---|---|
| A11 | 컨테이너 조작 | `open_container`, `click_slot`, `close_container` |
| A12 | 화로/제작대 | `smelt`, `craft` — 고수준 래퍼 |
| A13 | 주민 거래 | `interact` → 거래 GUI 열기 → 슬롯 조작 |
| A14 | 메모리 저장 경로 | 월드 로드 시 `.agent/agents/default/` 디렉터리 생성 |

### Team B: Agent Runtime

| 순서 | 작업 | 설명 |
|---|---|---|
| B6 | GUI MCP tool | A11~A13에 대응하는 tool 등록 |
| B7 | 메모리 시스템 | `remember_*`, `recall` tool + 파일 읽기/쓰기 |
| B8 | 세션 시작 프롬프트 | 메모리 로드 → 프롬프트 주입 로직 |

### 의존성

```
A: [A11 컨테이너] → [A12 화로/제작대] → [A13 주민 거래]
   [A14 메모리 경로] ─────────────────────────────────→ 통합 테스트
                                                            ↑
B: [B6 GUI tool] → [B7 메모리 시스템] → [B8 세션 프롬프트] ─┘
```

**Phase 3 완료 기준**:
- `/agent 철광석 화로에서 구워` → 상자에서 재료 꺼내기 → 화로에 넣기 → 굽기 → 결과 꺼내기
- 다음 세션에서 `/agent 아까 그 창고에서 철 가져와` → 창고 위치를 기억하고 있음

---

## Phase 4 — 모니터링 + 모드 호환

**목표**: terminal-mod에서 실시간 모니터링, AE2/Create 기본 연동

### Team A: Forge Mod

| 순서 | 작업 | 설명 |
|---|---|---|
| A15 | Monitor 로그 수신 | `POST /log` 엔드포인트, 로그 버퍼 관리 |
| A16 | 채팅 출력 | 로그를 인게임 채팅에 표시 (요약 레벨) |
| A17 | terminal-mod 연동 | optional dep, `ModList.isLoaded("terminal")` → 전용 탭 |
| A18 | 개입 시스템 | `GET /intervention` + `/agent stop/pause/resume` |

### Team B: Agent Runtime

| 순서 | 작업 | 설명 |
|---|---|---|
| B9 | 로그 스트리밍 | SDK 메시지 → `POST /log`로 실시간 전달 |
| B10 | 개입 수신 | `GET /intervention` 폴링 → 다음 턴 컨텍스트에 주입 |

### Team C: Mod Compat (A+B 합류)

| 순서 | 작업 | 설명 |
|---|---|---|
| C1 | Compat 프레임워크 | Action/Observation 등록 인터페이스, optional dep 패턴 |
| C2 | AE2 연동 | `ae2_search`, `ae2_request_craft` — AE2 Grid API 사용 |
| C3 | Create 연동 | `create_get_kinetic` — Create 기계 상태 조회 |
| C4 | MCP tool 자동 등록 | `GET /actions`로 사용 가능한 Action 목록 → MCP tool 동적 생성 |

### 의존성

```
A: [A15 로그 수신] → [A16 채팅] → [A17 terminal-mod] → [A18 개입]
                                                              ↓
B: [B9 로그 스트림] → [B10 개입 수신] ────────────────────→ 통합 테스트
                                                              ↑
C: [C1 프레임워크] → [C2 AE2] → [C3 Create] → [C4 자동등록] ─┘
```

**Phase 4 완료 기준**:
- terminal-mod 탭에서 Agent 사고/행동 실시간 확인
- 탭에서 "다이아 곡괭이로 바꿔" 입력 → Agent가 반영
- `/agent AE2에서 다이아몬드 몇 개 있어?` → AE2 네트워크 조회 → 응답

---

## Phase별 요약

| Phase | 목표 | Team A (Forge) | Team B (Runtime) | Team C (통합) |
|---|---|---|---|---|
| 0 | 프로젝트 셋업 | 모드 스캐폴딩 | Node.js 셋업 | - |
| 1 | 뼈대 | FakePlayer + HTTP + 이동 | SDK PoC + MCP 기본 | - |
| 2 | 기본 행동 | Pathfinding + 블록/아이템/엔티티 | MCP 확장 + 작업 요약 | - |
| 3 | GUI + 메모리 | 컨테이너 + 거래 | 메모리 tool + 세션 프롬프트 | - |
| 4 | 모니터링 + 모드 | Monitor + terminal-mod 연동 | 로그 + 개입 | AE2/Create 연동 |

---

## TODO: Agent TUI (terminal-mod 연동 대안)

Phase 4의 A17(terminal-mod 연동)은 Java 레벨 API 연동 대신 **독립 CLI/TUI**로 구현한다.

### 아이디어

terminal-mod는 진짜 PTY 터미널이므로, 그 안에서 CLI 프로그램을 실행하면 별도 연동 코드 없이 모니터링/개입이 가능하다.

```
terminal-mod 탭에서:
$ agent-tui

┌─ Agent Monitor ─────────────────────────────┐
│ [사고] 철광석을 찾는 중...                    │
│ [행동] > get_observation                      │
│ [행동] > move_to(3, 63, 0)                    │
│ [행동] > mine_block(3, 63, 0)                 │
│ [완료] 5턴 소요                               │
├─────────────────────────────────────────────│
│ > 남쪽은 건너뛰어                  [Enter 개입] │
└─────────────────────────────────────────────┘
```

### 장점

- **Java 연동 코드 0줄** — terminal-mod, agent-mod 모두 수정 불필요
- **독립 실행 가능** — 일반 터미널(PowerShell, iTerm)에서도 사용 가능
- **HTTP bridge만 있으면 동작** — agent-mod의 기존 엔드포인트 활용
- **Node.js TUI** — 빠른 개발 (ink, blessed 등 라이브러리)

### 기능

| 기능 | HTTP 엔드포인트 | 방향 |
|---|---|---|
| 상태 표시 | `GET /status` | TUI ← bridge |
| 에이전트 소환/제거 | `POST /spawn`, `/despawn` | TUI → bridge |
| 실시간 로그 | `POST /log` 폴링 | TUI ← bridge |
| 현재 상태 조회 | `GET /observation` | TUI ← bridge |
| 작업 지시 | RuntimeManager를 통해 | TUI → bridge |
| 플레이어 개입 | `POST /intervention` | TUI → bridge → agent-runtime |

### 구현 위치

`agent-runtime/` 내에 `tui/` 디렉토리 또는 별도 `agent-tui/` 패키지.
bridge-server.json에서 포트를 읽어 자동 연결.

### 비고

이 방식을 채택하면 Phase 4의 A17(TerminalIntegration.java)는 삭제 가능.
A16(ChatMonitor)은 TUI 없이도 동작하는 fallback으로 유지.

---

## 검증 시나리오 (MVP 완료 기준)

```
시나리오 1: 기본 자원 수집
  /agent 철 64개 캐서 창고에 넣어
  → pathfinding → 채굴 → 줍기 → 이동 → 상자에 넣기

시나리오 2: GUI 조작
  /agent 화로에서 철광석 32개 구워
  → 상자에서 재료/연료 꺼내기 → 화로 조작 → 결과물 회수

시나리오 3: 세션 간 기억
  /agent 이 위치를 "메인 창고"로 기억해
  (다음 세션)
  /agent 메인 창고에서 다이아 가져와
  → 기억된 좌표로 이동

시나리오 4: 모니터링 + 개입
  /agent 언덕 평탄화 20x20
  → terminal-mod 탭에서 진행 상황 확인
  → "남쪽은 건너뛰어" 개입 → 반영

시나리오 5: 모드 연동
  /agent AE2 네트워크에서 구리 100개 꺼내서 Create 믹서에 넣어
  → AE2 API 조회 → 아이템 추출 → 이동 → Create 컨테이너 투입
```
