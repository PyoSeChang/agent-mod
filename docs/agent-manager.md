# Agent Manager

## 개요

Agent Manager는 마인크래프트 세계에서 직접 행동하지 않는 **메타 관리 런타임**이다.
기존 agent들이 FakePlayer로 세계 안에서 행동하는 것과 달리, agent-manager는 agent CRUD, 스케줄 관리, observer 등록, 향후 오케스트레이션(채팅방, 협업)을 담당한다.

## 왜 필요한가

기존 agent에게 observer 등록, 스케줄 관리 같은 인프라성 MCP 도구를 주면:
- agent의 context가 행동과 무관한 관리 도구로 오염됨
- "밀 수확해"를 처리하는 agent가 "observer 등록" 도구까지 알 필요 없음
- 향후 멀티 에이전트 오케스트레이션 시 중앙 조율자가 없음

## 아키텍처

```
유저
  │
  ├── /am "밀 농장 자동 수확 세팅해줘"     ← agent-manager 직접 호출
  ├── /agent @alex "나무 10개 캐와"         ← 기존 agent 직접 호출
  │
  ▼
Agent Manager (Claude SDK 런타임, 상주 프로세스)
  │
  ├── Schedule/Observer 관리 (MCP tools)
  │     → Java 모드에 observer 등록
  │     → Java 모드에 schedule 등록
  │
  ├── Agent CRUD
  │     → spawn/despawn/persona 수정
  │
  ├── 트리거 수신 (Java → agent-manager)
  │     → "observer 조건 충족" / "TIME_OF_DAY 도달" / "INTERVAL 만료"
  │     → 해당 agent에게 promptMessage 전달
  │
  └── (향후) 오케스트레이션
        → 채팅방, 협업, 작업 분배
```

## 기존 agent와의 차이

| | Agent (alex, steve...) | Agent Manager |
|---|---|---|
| FakePlayer | O | X |
| 마크 세계 행동 | O (move, mine, craft...) | X |
| MCP 도구 | 행동 도구 (move_to, mine_block...) | 관리 도구 (schedule, observer, agent CRUD) |
| 런타임 | 명령 시 실행, 작업 완료 시 종료 | 상주 프로세스, transcript 유지 |
| context | 마크 세계 상태에 집중 | 에이전트/스케줄 메타 정보에 집중 |

## 인게임 커맨드

```
/am <message>              # agent-manager에게 자연어 명령
/am schedule list          # 스케줄 목록
/am observer list          # observer 목록
```

`/am`은 agent-manager의 약어. 기존 `/agent`와 네임스페이스 분리.

## 런타임 특성

- 별도 Claude SDK 프로세스 (Node.js)
- 서버 시작 시 자동 실행 (또는 첫 `/am` 호출 시)
- **transcript 유지** — `/am` 호출 시 기존 대화 맥락 이어감
- 트리거 수신 시 SDK query로 판단 후 agent에게 메시지 전달

## MCP 도구 (agent-manager 전용)

| Tool | 설명 |
|------|------|
| `create_schedule` | 스케줄 생성 (TIME_OF_DAY, INTERVAL, OBSERVER) |
| `update_schedule` | 스케줄 수정 |
| `delete_schedule` | 스케줄 삭제 |
| `list_schedules` | 스케줄 목록 조회 |
| `register_observer` | observer 등록 (위치, 이벤트 타입, 조건) |
| `remove_observer` | observer 제거 |
| `list_observers` | observer 목록 |
| `spawn_agent` | agent 스폰 |
| `despawn_agent` | agent 디스폰 |
| `tell_agent` | agent에게 메시지 전달 |
| `list_agents` | agent 목록 |

## 트리거 → agent 전달 흐름

```
1. Java: 게임 틱/이벤트 감시
2. Java: 조건 충족 감지
3. Java → HTTP → agent-manager 런타임: "schedule X 트리거됨"
4. agent-manager: SDK query → 판단 (어떤 agent에게, 어떤 메시지로)
5. agent-manager → Java → agent 런타임: promptMessage 전달
6. agent: 실제 행동 수행
```

## 향후 확장

- 채팅방: 에이전트 간 메시지 라우팅
- 협업: 복합 작업을 여러 agent에게 분배
- 모니터링: agent 상태 대시보드
- 조건부 워크플로우: "alex가 끝나면 steve 시작"
