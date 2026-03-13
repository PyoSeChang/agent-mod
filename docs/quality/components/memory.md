# memory — CHANGELOG

**지식 영속화** 시스템 — Brain의 장기 기억.
Key files: `MemoryManager`, `MemoryEntry`, `MemoryLocation`, `ObservationBuilder` (memories), `MemoryListScreen`, `MemoryEditScreen`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 메모리 스코핑 + GUI 개선.

**스코핑**
- `MemoryManager`: 전면 재작성. `globalEntries` ← `.agent/memory.json`, `agentEntries: Map<String, List>` ← `.agent/agents/{name}/memory.json`
  - `loadAgent(name)`, `unloadAgent(name)`, `saveAgent(name)`, `saveAll()`
  - `search(query, category, scope)` — scope-aware 검색 (global / agent:name / all)
  - `searchAll()` — 전체 스코프 검색
- `ObservationBuilder`: `build(agent, level, agentName)` — 글로벌+에이전트 메모리 병합 주입

**엔드포인트**
- `/agent/{name}/memory/*` → scope=`agent:name`
- `/memory/search` scope 파라미터 지원

**GUI 개선**
- `MemoryListScreen`: 스코프 탭 행 추가 (`[All] [Global] [agent1] ...`), 검색 scope 파라미터 전달
- `MemoryEditScreen`: 스코프 선택 버튼, 생성 시 스코프별 엔드포인트 라우팅, 부모 화면 스코프 상속

---

## v0.2.5 — 2026-03-12

통합 메모리 시스템 초기 구현.

**데이터 모델**
- `MemoryEntry`: 통합 데이터 모델 (id, title, description, content, category, tags, location, scope, timestamps)
- `MemoryLocation`: point/area 위치 모델 — distanceTo(), isWithinRange()

**영속화**
- `MemoryManager`: 싱글턴 CRUD + JSON persistence (atomic write) + CopyOnWriteArrayList (스레드 안전)
  - Auto-load 규칙: preference=항상, storage/facility/area=32블록 이내, event=최신 5개
  - ID: 순차 `m001`, `m002` (ergonomic)
  - 저장: `.agent/memory.json`

**엔드포인트**
- `/memory/create`, `/memory/get`, `/memory/update`, `/memory/delete`, `/memory/search`

**Observation 주입**
- `ObservationBuilder`: `memories` 섹션 추가 — `title_index` (전체 거리순) + `auto_loaded` (카테고리별 content 주입)

**GUI**
- `M` 키바인드 → `MemoryListScreen` (카테고리 탭 + 검색 + 거리순 리스트) + `MemoryEditScreen` (편집/생성/삭제)
- `AreaMarkHandler`: 월드에서 블록 2개 우클릭으로 영역 코너 지정

**Brain 연동**
- MCP 도구 5개: `remember`, `update_memory`, `forget`, `recall`, `search_memory`
- 시스템 프롬프트 Memory system 섹션 추가

---

## v0.1.0–v0.2.4

메모리 시스템 없음. v0.1.0의 `remember_location`/`remember_facility`/`remember_preference`/`recall`은 stub 수준.
