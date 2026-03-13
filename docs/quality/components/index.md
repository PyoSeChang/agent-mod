# Component Index

agent-mod의 6개 핵심 컴포넌트. CHANGELOG와 시나리오에서 태그로 참조.

| ID | 이름 | 범위 | 최종 변경 | 상태 |
|----|------|------|----------|------|
| `brain` | Brain | Claude의 선택 (프롬프트, 도구 설명, 설정) | v0.3.0 | Active |
| `actions` | Actions | 액션 구현 (Java) — 동작 방식 결정 | v0.3.0 | Active |
| `body` | Body | 시각/물리적 존재감 (애니메이션, 틱 처리) | v0.3.0 | Active |
| `multi-agent` | Multi-Agent | 에이전트 수명주기, 페르소나, 명령, 관리 GUI | v0.3.0 | Active |
| `memory` | Memory | 지식 영속화, 스코핑, 메모리 GUI | v0.3.0 | Active |
| `infra` | Infra | 플러밍 (HTTP 브릿지, 패스파인딩, 런타임, 로깅) | v0.3.0 | Active |

## 컴포넌트별 CHANGELOG

- [brain.md](brain.md) — 시스템 프롬프트, MCP 도구 설명, 런타임 설정
- [actions.md](actions.md) — Java 액션 구현
- [body.md](body.md) — FakePlayer 애니메이션, 틱 처리
- [multi-agent.md](multi-agent.md) — 멀티 에이전트 수명주기, 페르소나, 명령 체계, 관리 GUI
- [memory.md](memory.md) — 메모리 시스템, 스코핑, 메모리 GUI
- [infra.md](infra.md) — HTTP 브릿지, 패스파인딩, 런타임 프로세스, 로깅

---

## 컴포넌트 정의

### `brain`

Claude가 **무엇을 선택하는지** 결정하는 레이어.

| 파일 | 역할 |
|------|------|
| `agent-runtime/src/prompt.ts` | 시스템 프롬프트 (페르소나 동적 주입) |
| `agent-runtime/src/mcp-server.ts` (도구 설명) | 도구 이름, description, 파라미터 스키마. PERSONA 기반 필터링 |
| `agent-runtime/src/index.ts` (maxTurns 등) | 런타임 설정, per-agent 세션 |

**변경 시 영향**: 같은 액션이 존재해도 Claude가 선택하는 도구, 호출 순서, 파라미터가 달라짐.

### `actions`

개별 액션 구현 (Java). 액션이 **어떻게 동작하는지** 결정.

| 파일 | 역할 |
|------|------|
| `core/action/*.java` (실행 로직) | 각 액션의 tick/execute 구현 |
| `mcp-server.ts` (파라미터 전달) | bridge fetch 호출 형태 |

**변경 시 영향**: 같은 도구를 호출해도 결과(성공/실패, 부작용)가 달라짐.

### `body`

에이전트의 **시각적/물리적 존재감**.

| 파일 | 역할 |
|------|------|
| `core/AgentAnimation.java` | lookAt, swingArm 패킷 브로드캐스트 |
| `core/AgentTickHandler.java` | 전체 에이전트 순회 — 자동 아이템 흡수, 액션 tick |

**변경 시 영향**: 기능적 결과는 같지만 플레이어가 보는 행동이 달라짐.

### `multi-agent`

**멀티 에이전트 수명주기**, 페르소나, 명령 체계, 관리 GUI.

| 파일 | 역할 |
|------|------|
| `core/AgentContext.java` | 에이전트별 상태 번들 (FakePlayer, ActionManager, Persona, 세션) |
| `core/AgentManager.java` | 멀티 에이전트 스폰/디스폰, 가시성 패킷 |
| `core/PersonaConfig.java` | PERSONA.md 파서 (역할, 성격, 도구, 지인) |
| `command/AgentCommand.java` | `/agent spawn/despawn/tell/list/status/stop` 명령 |
| `monitor/InterventionQueue.java` | 에이전트별 개입 큐 |
| `client/AgentManagementScreen.java` | 에이전트 관리 GUI (G키) |

**변경 시 영향**: 에이전트 생성/삭제/스폰 동작, 페르소나 적용, 명령 라우팅, 에이전트 간 격리 수준.

### `memory`

**지식 영속화** 시스템 — Brain의 장기 기억.

| 파일 | 역할 |
|------|------|
| `core/memory/MemoryManager.java` | 글로벌+에이전트별 메모리 CRUD, 스코핑, JSON 영속화 |
| `core/memory/MemoryEntry.java` | 메모리 데이터 모델 |
| `core/memory/MemoryLocation.java` | point/area 위치 모델 |
| `core/ObservationBuilder.java` (memories 섹션) | title_index + auto_loaded Observation 주입 |
| `client/MemoryListScreen.java` | 메모리 목록 GUI (M키) — 스코프 탭, 검색 |
| `client/MemoryEditScreen.java` | 메모리 편집/생성 GUI — 스코프 선택 |
| `client/AreaMarkHandler.java` | 영역 코너 지정 (메모리 area 타입) |

**변경 시 영향**: Brain의 장기 기억 동작, 세션 간 지식 유지, 메모리 스코핑 범위.

### `infra`

안정성과 성능을 결정하는 **순수 플러밍**.

| 파일 | 역할 |
|------|------|
| `network/AgentHttpServer.java` | HTTP 브릿지, 라우팅 코어 |
| `core/pathfinding/Pathfinder.java` | A* 패스파인딩 |
| `core/pathfinding/PathFollower.java` | 틱 기반 이동 |
| `runtime/RuntimeManager.java` | per-agent 런타임 프로세스 관리 |
| `core/action/AsyncAction.java` | 비동기 액션 인터페이스, 타임아웃 |
| `core/action/ActiveActionManager.java` | 에이전트별 액션 생명주기 관리 |
| `core/AgentLogger.java` | 구조화 JSONL 로거 |
| `client/BridgeClient.java` | GUI → HTTP bridge 비동기 통신 |
| `monitor/ChatMonitor.java` | 채팅 모니터링, word-wrap |

**변경 시 영향**: 액션 타임아웃, 이동 실패, 스레드 안전성, 통신 안정성.
