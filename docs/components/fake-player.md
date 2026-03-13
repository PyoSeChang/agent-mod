# Multi-Agent FakePlayer System

월드에 존재하는 이름 기반 복수 FakePlayer 엔티티. 실제 플레이어와 동일한 방식으로 블록/엔티티와 상호작용.

## 특성

- Forge의 `FakePlayer`(ServerPlayer 확장) 기반
- 서버 사이드에서만 동작 (헤드리스)
- 물리적 존재 — 다른 플레이어/엔티티에게 보임
- MVP에서는 체력/허기/경험치 등 플레이어 속성 없음 (무적, 배고픔 없음)
- 인벤토리만 보유 (행동 수행에 필요한 최소 상태)

## 멀티 에이전트 구조

- `AgentManager`: `ConcurrentHashMap<String, AgentContext>`로 에이전트 관리
- `AgentContext`: 에이전트별 상태 번들 (FakePlayer, ActionManager, InterventionQueue, PersonaConfig, 런타임 프로세스)
- GameProfile: `[name]` 형식 표시 이름, UUID = `nameUUIDFromBytes("Agent_" + name)`
- 에이전트 간 완전 격리: ActionManager, InterventionQueue 각자 인스턴스

## 스폰/디스폰

- `AgentManager.spawn(name, level, pos)`: FakePlayer 생성 + PERSONA.md 로드 + per-agent 메모리 로드 + 가시성 패킷
- `AgentManager.despawn(name)`: 메모리 저장 + FakePlayer 제거 + 가시성 패킷
- `AgentManager.despawnAll()`: 서버 종료 시 전체 정리

## 페르소나

- `.agent/agents/{name}/PERSONA.md`에서 역할, 성격, 허용 도구 정의
- `PersonaConfig.parse()`: `## Role`, `## Personality`, `## Tools` 섹션 파싱
- 도구 목록 비어있으면 전체 허용, 명시되면 해당 도구만 MCP에 등록
- 적용: despawn → PERSONA.md 수정 → respawn
