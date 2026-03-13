# multi-agent — CHANGELOG

**멀티 에이전트 수명주기**, 페르소나, 명령 체계, 관리 GUI.
Key files: `AgentContext`, `AgentManager`, `PersonaConfig`, `AgentCommand`, `InterventionQueue`, `AgentManagementScreen`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 시스템 전면 구현.

**에이전트 수명주기**
- `AgentContext.java` 신규: 에이전트별 상태 번들 (FakePlayer, ActionManager, InterventionQueue, PersonaConfig, 런타임 프로세스, 세션ID)
- `AgentManager.java` 신규: `ConcurrentHashMap<String, AgentContext>` 기반 멀티 에이전트 관리. `FakePlayerManager` 대체
- `InterventionQueue`: 싱글턴 제거 → 에이전트별 인스턴스

**페르소나 시스템**
- `PersonaConfig.java` 신규: `.agent/agents/{name}/PERSONA.md` 파서
  - `## Role`, `## Personality`, `## Tools` 섹션
  - `## Acquaintances` 섹션: `Acquaintance` record (name, description) — skill-like 지인 정보
  - `defaultPersona()`: PERSONA.md 없을 때 기본 생성
  - `isAllToolsAllowed()`: tools 빈 리스트 = 전체 허용
- `AgentManager.spawn()`: PERSONA.md 자동 로드 → PersonaConfig 생성

**명령 체계**
- `AgentCommand`: 전면 재작성 — `/agent spawn <name>`, `/agent despawn <name>`, `/agent tell <name> <msg>`, `/agent list`, `/agent status <name>`, `/agent stop <name>`, `/agent pause <name>`, `/agent resume <name>`. 탭 완성 지원

**관리 GUI**
- `AgentManagementScreen.java` 신규 (G키): 좌측 에이전트 목록 (스폰 상태 표시) + 우측 페르소나 편집 (역할/성격/도구 체크박스/지인 편집)
- 지인 편집: 목록 렌더링 + [x] 제거 + 추가 입력 (name + description + [+] 버튼)

**지인(Acquaintance) 시스템**
- Observation에 `acquaintances` 섹션 주입: name, description, spawned 여부, distance, position
- 향후 협업/채팅 기능의 기반 — 에이전트가 누구와 소통 가능한지 인지
- PERSONA.md에 명시된 관계만 인지 (전체 공유 아님)

---

## v0.1.0–v0.2.5

싱글 에이전트 구조. `FakePlayerManager`로 단일 FakePlayer 관리.
멀티 에이전트 컴포넌트로서의 변경 없음.
