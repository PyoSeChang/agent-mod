# Action System (행동)

## 설계 원칙

1. **Atomic**: 마인크래프트가 플레이어에게 제공하는 최소 단위 입력을 MCP tool로 제공
2. **Player-like**: God Mode 금지. 실제 플레이어가 할 수 있는 행동만 허용
3. **Minecraft 로직 위임**: 이동/채굴 등 모션이 필요한 부분은 Java에서 틱 단위로 처리

## Layer 1: Atomic Actions (MCP Tools)

마인크래프트 플레이어의 기본 입력에 대응하는 최소 단위.

| Action | 설명 | 마인크래프트 대응 |
|---|---|---|
| `move_to(x, y, z)` | 목적지까지 걸어서 이동 | W/A/S/D + 점프 |
| `useItemOn(x, y, z)` | 손에 든 아이템을 블록에 사용 | 우클릭 (호미→경작, 씨앗→심기, 양동이→물, 문→열기 등) |
| `useItem()` | 손에 든 아이템 사용 | 우클릭 (음식 먹기, 활 쏘기, 물약 마시기 등) |
| `attack(x, y, z)` | 블록/엔티티 공격 | 좌클릭 |
| `mine_block(x, y, z)` | 블록 채굴 (도구 경도에 따른 시간 소요) | 좌클릭 꾹 누르기 |
| `interact(entity_id)` | 엔티티와 상호작용 | 우클릭 (주민 거래, 동물 먹이주기 등) |
| `equip(item, slot)` | 아이템 장비 | 인벤토리 조작 |
| `pickup_items(radius)` | 주변 아이템 줍기 | 아이템 위로 걸어가기 |
| `drop_item(item, count)` | 아이템 버리기 | Q키 |

## Layer 1: Container Actions

| Action | 설명 |
|---|---|
| `open_container(x, y, z)` | 상자/화로/작업대 등 열기 |
| `click_slot(slot, action)` | GUI 슬롯 클릭 |
| `close_container()` | GUI 닫기 |

## Layer 2: Minecraft 로직 (Java 측 최적화)

Agent가 atomic action을 호출하면, Java에서 실제 플레이어처럼 수행한다.

| 행동 | God Mode (이전) | Player-like (목표) |
|---|---|---|
| 이동 | `setPos()` 텔레포트 | A* pathfinding → 매 틱 걷기 |
| 채굴 | `destroyBlock()` 즉시 | 도구 경도 기반 틱 단위 채굴 |
| 블록 배치 | `setBlock()` 아무거나 | 인벤토리에 있는 블록만, 손 닿는 범위 |
| 경작 | `setBlock(farmland)` | 호미 equip → useItemOn(dirt) |
| 씨앗 심기 | `setBlock(wheat)` | 씨앗 equip → useItemOn(farmland) |
| 제작 | 레시피 → 아이템 생성 | 작업대 open → 슬롯 배치 → 결과 꺼내기 |

### 핵심: useItemOn

`useItemOn`은 마인크래프트 상호작용의 핵심. 이 하나로:
- 호미로 땅 갈기
- 씨앗/작물 심기
- 양동이로 물 퍼기/놓기
- 문/레버/버튼 조작
- 모드 아이템의 블록 상호작용 전부

## 모드 행동 (Mod Compat Layer)

모드별 특수 행동은 skill로 정의하고, 실행은 atomic action 조합 또는 전용 MCP tool.

| Action | 모드 | 설명 |
|---|---|---|
| `ae2_search(item)` | AE2 | ME 네트워크 아이템 검색 |
| `ae2_request_craft(item, count)` | AE2 | 자동 크래프팅 요청 |
| `create_get_kinetic(pos)` | Create | 회전력 정보 조회 |

모드 행동은 가능한 한 atomic action 조합으로 수행하되,
모드 API를 직접 호출해야만 하는 경우(AE2 네트워크 조회 등)만 전용 tool로 제공.
