# actions — CHANGELOG

개별 액션 구현 (Java). 액션이 **어떻게 동작하는지** 결정.
Key files: `core/action/*.java`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 대응.

- `ActiveActionManager`: 싱글턴 제거 → 에이전트별 인스턴스
- `MineBlockAction`, `MineAreaAction`: `cachedAgent` 패턴으로 cancel() 시 참조 유지

---

## v0.2.3 — 2026-03-12

컨테이너 조작 개선.

- `OpenContainerAction`: 열린 컨테이너의 모든 슬롯 내용물을 `slots` 배열로 반환 (I-008)
- `ClickSlotAction` 피드백 강화: before/after 슬롯 상태 + carried 아이템 반환
  - 파라미터명 다형성: `click_action`, `action_type`, `action` 순서로 읽기 (execute_sequence 경유 대응)
  - `action` 필드 우선순위 최하위로 변경 — execute_sequence 충돌 버그 수정 (I-013)
- `ContainerTransferAction` 신규 (내부 전용): ActionRegistry에만 등록, MCP 미노출

---

## v0.2.2 — 2026-03-12

환경 피드백 raw fact 보고.

- `UseItemOnAreaAction`: 실패 시 `failure_details` 배열 추가 — pos, block, above, result, dist (raw fact)
- `UseItemOnAreaAction`: 대상 블록 위 블록 정보(`above`) substep 로그에 추가
- `UseItemOnAreaAction`: 경로 실패(no_path, no_standing_position)도 failure_details에 포함

---

## v0.2.1 — 2026-03-12

Equip 수정 + pickup_items 제거.

- `EquipAction`: 아이템 1개만 장착 → 전체 스택 장착 (I-002 수정)
- `PickupItemsAction` 제거 (legacy) — 2블록 자동흡수로 대체

---

## v0.2.0 — 2026-03-11

영역 액션 + 시퀀스 + 실제 제작.

- `CraftAction` 전면 재작성: 레시피 조회 전용 → 실제 재료 소비 + 결과물 생성, `count` 파라미터
- `MineAreaAction` 신규: 영역 채굴 (걷기+채굴 상태 머신, 최대 256블록)
- `UseItemOnAreaAction` 신규: 2D 영역 우클릭 (서펜타인 패턴)
- `SequenceAction` 신규: 액션 배열 순차 실행, sync 체이닝
- 기존 액션에 `AgentAnimation.lookAt()` + `swingArm()` 추가

---

## v0.1.0 — 2026-03-11

초기 구현.

- 15개 기본 액션 구현 (move_to, mine_block, place_block, equip, attack 등)
- craft/smelt은 레시피 조회만 (실제 제작 불가)
