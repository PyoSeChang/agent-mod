# 아키텍처

## 설계 철학: Player-like Agent

Agent는 전지전능한 God Mode가 아니라 **실제 플레이어처럼 행동**한다.
텔레포트 대신 걷고, 블록을 즉시 파괴하지 않고 도구로 채굴하고, 호미로 땅을 갈아서 경작한다.

이를 위해 4개 계층으로 분리한다:

```
Layer 4: Skills (절차적 지식)
  "철 곡괭이 = 작업대에서 철3+막대2"
  "Create 믹서는 블레이즈 버너 위에 배치"
  → Memory에서 좌표/상태 참조, skill 자체에 하드코딩 X
  → 월드/모드팩마다 다른 지식

Layer 3: Memory (월드 상태 지식)
  Session: 작업 진행 상태, 이번에 관찰한 것, 대화 맥락
  Persistent: 장소, 시설, 창고 내용물, 선호도, 작업 이력
  → 별도 레이어로 분리, skill은 "뭘 참조할지"만 앎
  → Global (월드 공유) + Agent-specific (개인)
  → 상세: memory.md 참조

Layer 2: Minecraft 로직 (Java 최적화)
  pathfinding으로 걷기, 틱 단위 채굴 모션, 엔티티 물리
  → agent가 "move_to" 하면 Java가 실제 플레이어처럼 수행
  → agent는 결과만 받음 (성공/실패/소요시간)

Layer 1: Atomic MCP Tools (최소 단위 입력)
  useItemOn, attack, interact, openContainer, clickSlot...
  → 마인크래프트가 플레이어에게 제공하는 입력 그 자체
  → PERSONA.md에서 에이전트별로 허용 도구 필터링
```

### 왜 Player-like인가

| God Mode | Player-like |
|---|---|
| `setBlock(farmland)` | 호미 들고 `useItemOn(dirt)` |
| `setPos(x,y,z)` 텔레포트 | pathfinding + 매 틱 걷기 |
| `destroyBlock()` 즉시 파괴 | 도구 들고 틱 단위 채굴 |
| 레시피 → 아이템 즉시 생성 | 작업대 열기 → 슬롯 배치 → 제작 |

God Mode는 치트 NPC. Player-like는 진짜 동료 플레이어.
Player-like여야 모드팩 호환이 자연스럽다 — 모든 모드는 플레이어 입력을 전제로 설계되어 있으므로.

### 예시: "농사 지어"

```
Skill: "밀 농사"
  → Memory 참조: recall("작업대"), recall("밭")

Agent 판단:
  1. 나무 캐기 → 작업대 제작 → 괭이 제작
  2. move_to(밭 위치)
  3. equip(괭이) → useItemOn(흙) → farmland 생성
  4. useItemOn(씨앗) → 심기
  5. 기다리기 → 수확

Java 실행 (각 단계):
  - move_to: pathfinding → 매 틱 한 블록씩 걷기
  - useItemOn: 실제 FakePlayer가 호미를 들고 블록에 우클릭
  - 채굴: 도구 경도에 따른 틱 단위 채굴 시간
```

---

## 시스템 구조 — 멀티 에이전트

```
┌──────────────────────────────────────────────────────┐
│  Minecraft Server (Forge 1.20.1)                     │
│                                                      │
│  ┌──────────────────────────────┐   ┌────────────┐   │
│  │  agent-mod                   │   │terminal-mod│   │
│  │                              │   │ (optional)  │   │
│  │  AgentManager                │   │            │   │
│  │  ├─ AgentContext "alex"      │   │  터미널     │   │
│  │  │  ├─ FakePlayer            │   │  렌더링     │   │
│  │  │  ├─ ActionManager         │   │            │   │
│  │  │  ├─ PersonaConfig         │   │            │   │
│  │  │  └─ InterventionQueue     │   │            │   │
│  │  ├─ AgentContext "steve"     │   └────────────┘   │
│  │  │  └─ ...                   │                    │
│  │  │                           │                    │
│  │  Action System (Player-like) │                    │
│  │  Observation System          │                    │
│  │  Memory (Global + Per-agent) │                    │
│  │  Mod Compat Layer            │                    │
│  │  HTTP Bridge                 │                    │
│  └──────────────┬───────────────┘                    │
└─────────────────┼────────────────────────────────────┘
                  │ HTTP (localhost, per-agent routing)
                  │ /agent/{name}/observation
                  │ /agent/{name}/action
                  │
    ┌─────────────┼──────────────────────┐
    │             │                      │
┌───┴────────┐ ┌─┴──────────┐  ┌────────┴───┐
│ Runtime    │ │ Runtime    │  │ Runtime    │
│ "alex"     │ │ "steve"    │  │ ...        │
│            │ │            │  │            │
│ Claude SDK │ │ Claude SDK │  │ Claude SDK │
│ MCP Server │ │ MCP Server │  │ MCP Server │
│ (filtered) │ │ (filtered) │  │ (filtered) │
└────────────┘ └────────────┘  └────────────┘

각 Runtime은 독립 Node.js 프로세스:
- AGENT_NAME, AGENT_TOOLS, AGENT_PERSONA_CONTENT env var
- MCP tool list = PERSONA.md의 ## Tools에 따라 필터링
- 세션 컨텍스트 독립 유지
```

## 모듈 구성

| 모듈 | 책임 | 비고 |
|---|---|---|
| **agent-mod** | Player-like 행동 실행 + 멀티 에이전트 관리 + 모니터링 + 모드호환 | 단일 Forge 모드 |
| **Agent Runtime** (per-agent) | 자연어 이해, 계획 수립, skill/memory 관리 | 에이전트당 독립 Node.js 프로세스 |
| **terminal-mod** | 터미널 렌더링 (Agent TUI 실행 가능) | optional, 별도 repo |

## agent-mod 내부 패키지

```
com.pyosechang.agent
├── core/           # AgentManager, AgentContext, PersonaConfig, Action, Observation, Memory, Pathfinding
├── client/         # GUI screens (AgentManagement, MemoryList, MemoryEdit), BridgeClient, Keybindings
├── compat/         # 모드 호환 (AE2, Create, Mekanism ...)
├── monitor/        # 로그 수신, 상태 표시, 개입 처리
├── command/        # /agent 명령어 (spawn, despawn, tell, list, status, stop, pause, resume)
├── network/        # HTTP Bridge (per-agent routing)
└── runtime/        # Per-agent 런타임 프로세스 관리
```

## 에이전트 수명주기

```
1. 정의:  .agent/agents/{name}/PERSONA.md 작성 (GUI 또는 파일 직접)
2. 스폰:  /agent spawn <name>  →  AgentManager.spawn()
           → PERSONA.md 로드 → AgentContext 생성 → FakePlayer 생성 → 가시성 패킷
           → per-agent memory 로드
3. 명령:  /agent tell <name> <msg>  →  RuntimeManager.launch()
           → Node.js 프로세스 생성 (AGENT_NAME, AGENT_TOOLS env)
           → MCP tools 페르소나 필터링 → Claude SDK 실행
4. 디스폰: /agent despawn <name>  →  AgentManager.despawn()
           → 런타임 중지 → 메모리 저장 → FakePlayer 제거
5. 수정:  G키 GUI → 페르소나 편집 → Save → despawn → respawn으로 적용
```

## 메모리 스코핑

```
.agent/
├── memory.json                    # Global (모든 에이전트 공유)
└── agents/
    ├── alex/
    │   ├── PERSONA.md
    │   └── memory.json            # Alex 전용
    └── steve/
        ├── PERSONA.md
        └── memory.json            # Steve 전용

Observation 주입 시: Global + 해당 에이전트 메모리 병합
GUI 검색: All / Global / 에이전트별 스코프 탭
```
