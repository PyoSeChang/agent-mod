# Action System (행동)

Agent가 수행할 수 있는 모든 행동의 집합. Claude SDK의 tool과 1:1 매핑.

## 기본 행동 (바닐라)

| Action | 설명 |
|---|---|
| `move_to(x, y, z)` | pathfinding으로 목적지 이동 |
| `mine_block(x, y, z)` | 블록 채굴 |
| `place_block(x, y, z, block)` | 블록 배치 |
| `pickup_items(radius)` | 주변 아이템 줍기 |
| `drop_item(item, count)` | 아이템 버리기 |
| `equip(item, slot)` | 장비 착용 |
| `attack(entity)` | 엔티티 공격 |
| `interact(entity)` | 엔티티 상호작용 (주민 거래 등) |

## GUI 행동 (바닐라 컨테이너)

| Action | 설명 |
|---|---|
| `open_container(x, y, z)` | 상자/화로/제작대 등 열기 |
| `click_slot(slot, action)` | GUI 슬롯 클릭 |
| `craft(recipe)` | 제작 |
| `smelt(input, fuel)` | 화로 굽기 |
| `close_container()` | GUI 닫기 |

## 모드 행동 (Mod Compat Layer를 통해 확장)

| Action | 모드 | 설명 |
|---|---|---|
| `ae2_search(item)` | AE2 | ME 네트워크 아이템 검색 |
| `ae2_request_craft(item, count)` | AE2 | 자동 크래프팅 요청 |
| `ae2_export(item, count, target)` | AE2 | 아이템 내보내기 |
| `create_get_kinetic(pos)` | Create | 회전력 정보 조회 |
| `mek_get_machine_status(pos)` | Mekanism | 기계 상태 조회 |
| `mek_set_input(pos, item)` | Mekanism | 기계에 아이템 투입 |
