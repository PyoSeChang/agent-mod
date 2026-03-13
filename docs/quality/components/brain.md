# brain — CHANGELOG

Claude가 **무엇을 선택하는지** 결정하는 레이어.
Key files: `prompt.ts`, `mcp-server.ts`, `index.ts`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 페르소나 + 지인 시스템.

- `PersonaConfig.java` 신규: `.agent/agents/{name}/PERSONA.md` 파서 (`## Role`, `## Personality`, `## Tools`, `## Acquaintances`)
- `Acquaintance` record (name, description) — skill-like 지인 정보
- `prompt.ts`: 정적 `SYSTEM_PROMPT` → `buildSystemPrompt()` 동적 생성. 페르소나 내용 + 에이전트 이름 주입
- `mcp-server.ts`: `AGENT_TOOLS` env var 기반 도구 필터링. `isAllowed()` 게이트로 조건부 등록 (15개 도구). `get_observation`, 메모리 도구, `execute_sequence`는 항상 등록
- `intervention.ts`, `index.ts`: per-agent URL 프리픽스 적용
- `ObservationBuilder`: `acquaintances` 섹션 Observation에 주입 — name, description, spawned 여부, distance, position

---

## v0.2.5 — 2026-03-12

메모리 시스템 — Brain이 세션 간 지식 유지.

- MCP 도구 5개 추가: `remember`, `update_memory`, `forget`, `recall`, `search_memory`
- `mcp-server.ts`: bridgeFetch → `/memory/*` 엔드포인트 연결
- 시스템 프롬프트 Memory system 섹션 추가 (title_index + auto_loaded, remember/recall 가이드)
- `ObservationBuilder`: `memories` 섹션 추가 — `title_index` (전체 거리순) + `auto_loaded` (카테고리별 content 주입)

---

## v0.2.4 — 2026-03-12

세션 컨텍스트 유지.

- `index.ts`: 첫 명령은 `sessionId`로 세션 생성 + 전체 시스템 프롬프트. 이후 명령은 `resume: sessionId`로 이전 대화 이어가기

---

## v0.2.3 — 2026-03-12

컨테이너 도구 개선 + 모델 prior 활용.

- MCP `click_slot` 설명 강화: before/after 상태 반환 명시, execute_sequence 연계 안내
- MCP `container_transfer` 제거 — 모델 prior와 일치하는 click_slot 패턴으로 전환
- `open_container` 설명 업데이트: 슬롯 내용물 반환 명시
- 시스템 프롬프트 가이드: open→review slots→click_slot(quick_move) + execute_sequence 패턴

---

## v0.2.1 — 2026-03-12

도구 가이드 + pickup_items 제거.

- 시스템 프롬프트에 Tool usage guide 섹션 추가 (mine_area, auto-collect, execute_sequence, use_item_on_area, craft count)
- `pickup_items` MCP 도구 제거

---

## v0.2.0 — 2026-03-11

영역 액션 + 시퀀스 도구 추가.

- `maxTurns`: 20 → 50
- MCP 도구 추가: `mine_area`, `use_item_on_area`, `execute_sequence`
- `craft` 도구에 `count` 파라미터 추가

---

## v0.1.0 — 2026-03-11

초기 구현.

- 시스템 프롬프트 (get_observation first, plan step-by-step, invulnerable)
- `maxTurns`: 20
- MCP 도구 22개: get_observation, move_to, mine_block, place_block, pickup_items, drop_item, equip, attack, interact, open_container, click_slot, close_container, craft, smelt, use_item_on, remember_location, remember_facility, remember_preference, recall
