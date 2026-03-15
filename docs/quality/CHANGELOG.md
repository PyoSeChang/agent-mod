# CHANGELOG

## v0.5.2 — 2026-03-15 `[multi-agent, infra]`

클라이언트 GUI 다국어(i18n) 지원. 한국어(ko_kr), 영어(en_us) lang 파일 추가. MCP 도구 목록에 사람이 읽기 좋은 번역 이름 + 호버 설명 툴팁 추가.

### 변경 사항

**`multi-agent`**
- `AgentManagementScreen.java`: 모든 하드코딩 문자열을 `Component.translatable()` / `I18n.get()`으로 전환
  - MCP 도구 이름: `mine_area` → "Mine Area" (en) / "영역 채굴" (kr), lang 키 없으면 원본 이름 fallback
  - 도구 호버 시 3줄 툴팁: 번역 이름(주황) + 설명(회색) + 원본 키(이탤릭)
- `MemoryListScreen.java`: 화면 타이틀, 카테고리/스코프 드롭다운, 검색, 버튼 텍스트 i18n 적용
- `MemoryEditScreen.java`: 필드 라벨, 버튼, visibility 토글, location 타입 텍스트 i18n 적용
- `AreaMarkHandler.java`: 코너 선택 채팅 메시지 i18n 적용

**`infra`**
- `assets/agent/lang/en_us.json` 신규: 영어 번역 파일 (GUI 65+ 키 + 도구 22쌍)
- `assets/agent/lang/ko_kr.json` 신규: 한국어 번역 파일

### 번역 키 구조

```
key.agent.*                → 키바인딩 이름
gui.agent.management.*     → 에이전트 관리 화면
gui.agent.memory.*         → 메모리 브라우저/편집 화면
gui.agent.category.*       → 공유 카테고리 라벨
tool.agent.<name>          → MCP 도구 표시 이름
tool.agent.<name>.desc     → MCP 도구 설명 (호버 툴팁)
```

## v0.4.1 — 2026-03-14 `[body, actions]`

PathFollower를 setPos 방식에서 deltaMovement 방식으로 전환 (바닐라 MoveControl 패턴). aiStep/travel과 협력하여 물리 기반 이동. MineBlockAction에 채굴 후 아이템 회수 이동 추가.

### 변경 사항

**`body`**
- `AgentManager.spawn()`: `setGameMode(GameType.SURVIVAL)` 강제 설정 — 서버 gameType(creative 등)에 관계없이 에이전트는 항상 survival. creative 모드에서 `destroyBlock`이 아이템 드롭하지 않는 문제 해결
- `PathFollower.java` 전면 재작성: `setPos()` 직접 위치 지정 → `setDeltaMovement()` 속도 설정 방식. aiStep/travel이 물리(충돌, 중력) 적용하여 실제 이동 처리
  - 바닐라 MoveControl 패턴 채용: PathFollower가 방향+속도 설정 → travel()이 적용
  - 점프: 다음 waypoint가 위에 있고 onGround일 때 JUMP_VELOCITY(0.42) 설정
  - waypoint 도달 판정: 수평 거리 < 0.3 && 수직 거리 < 1.0
- `AgentTickHandler`: tick 순서 변경 — actionManager.tick() → agent.tick(). PathFollower가 deltaMovement를 먼저 설정하고, aiStep/travel이 이를 적용

**`actions`**
- `MineBlockAction`: 상태 머신 추가 (MINING → COLLECTING). 채굴 완료 후 에이전트-블록 거리 > 2블록이면 블록 위치까지 pathfinding 이동하여 드롭 아이템 회수. 2블록 이내면 즉시 완료 (tick handler 자동 회수)

### 설계 판단

- **setPos vs deltaMovement**: 기존 setPos 방식은 aiStep의 물리 엔진과 매 틱 충돌 — aiStep이 중력(deltaMovement.y 누적)으로 아래로 끌어당기고 setPos가 다시 올리는 줄다리기 발생. deltaMovement 방식은 물리 엔진과 협력하여 중력, 충돌을 자연스럽게 처리
- **tick 순서 반전**: PathFollower가 deltaMovement를 설정한 후 aiStep/travel이 적용해야 하므로 actionManager.tick() → agent.tick() 순서로 변경
- **MineBlockAction 아이템 회수**: mining reach(~4.5블록) > pickup range(2블록) 차이로 먼 블록 채굴 시 드롭 아이템 미회수. 채굴 완료 후 블록 위치로 이동하여 tick handler의 자동 회수에 맡김

## v0.4.0 — 2026-03-14 `[body, actions, infra]`

FakePlayer → AgentPlayer(ServerPlayer subclass) 전환. 에이전트에 실제 플레이어 물리(중력, 충돌), 장비 동기화, 아이템 픽업을 부여.

### 변경 사항

**`body`**
- `AgentPlayer.java` 신규: ServerPlayer subclass. `tick()`에서 `ServerPlayer.tick()` + `Player.tick()` 체인(baseTick, detectEquipmentUpdates, aiStep) 수동 호출 — ServerPlayer.tick()이 super.tick()을 호출하지 않는 바닐라 설계를 보완
- `AgentNetHandler.java` 신규: mock ServerGamePacketListenerImpl. EmbeddedChannel + 리플렉션으로 Connection.channel 설정. 패킷 send/tick no-op
- `AgentManager.spawn()`: AgentPlayer 생성 → AgentNetHandler 설정 → PlayerInfo → addNewPlayer → AddPlayer + SetEntityData 패킷 전송
- `AgentManager.sendAgentInfoToPlayer()`: 후접속 플레이어에게 에이전트 패킷 전송
- `AgentTickHandler`: 20틱 주기 ClientboundTeleportEntityPacket 브로드캐스트 (클라이언트 위치 동기화)
- `FakePlayerManager.java` 삭제

**`actions`**
- `UseItemOnAction`: useItemOn() → PASS 시 useItem() fallback 추가 — 물 양동이, 엔더 진주 등 Item.use() 기반 아이템 지원
- 전 액션 FakePlayer → ServerPlayer 타입 전환 (Action, AsyncAction, 17개 구현체)

**`infra`**
- `RuntimeManager`: 비정상 종료(exit code != 0, 유저 stop 아닌 경우) 시 세션 자동 리셋 — 깨진 세션 resume 방지
- `AgentContext`: stoppedByUser 플래그 추가 — stop/despawn(유저 의도) vs 크래시 구분
- `build.gradle`: --add-opens JVM arg 추가 (리플렉션 모듈 접근)
- `ObservationBuilder`, `AgentHttpServer`, `AgentCommand`, `PathFollower`, compat 모듈: FakePlayer → ServerPlayer 전환

### 설계 판단

- **ServerPlayer.tick()은 super.tick()을 호출하지 않음**: 바닐라에서 플레이어 물리는 클라이언트(LocalPlayer)가 처리. 에이전트는 클라이언트가 없으므로 baseTick + detectEquipmentUpdates + aiStep을 직접 호출하여 중력, 충돌, 장비 동기화, 아이템 픽업 구현
- **detectEquipmentUpdates는 private**: LivingEntity의 private 메서드 — 리플렉션으로 접근. 이 메서드가 엔티티 트래커에 장비 변경을 알려서 손에 든 아이템이 다른 플레이어에게 보임
- **useItem fallback**: 바닐라 클라이언트는 useItemOn → PASS 시 useItem을 시도. 버킷류 아이템은 Item.use()에서 동작하므로 같은 fallback 필요
- **세션 리셋 vs 유지**: destroyForcibly(유저 stop)는 세션 유지하여 resume 가능. 진짜 크래시만 세션 리셋

### 알려진 이슈

- 클라이언트에서 에이전트가 약간 가라앉아 보이는 현상 (서버 Y좌표는 정상 — 엔티티 트래커 동기화 지연, TeleportEntityPacket 주기 전송으로 완화)
- respawn 시 인벤토리 초기화 (새 AgentPlayer 인스턴스 생성 — 인벤토리 저장/복원 미구현)

## v0.3.3 — 2026-03-13 `[multi-agent, memory, infra, brain]`

Multi-agent 격리 버그 3건 수정 + observation blockstate 고도화.

### 변경 사항

**`multi-agent`** (I-017)
- `ActionRegistry.java` 전면 재작성: sync/async 분리. async action은 `registerAsync(Class)` → `createAsync(name)`으로 매 실행마다 fresh instance 생성
- `AgentMod.java`: 5개 async action (MoveToAction, MineBlockAction, MineAreaAction, UseItemOnAreaAction, SequenceAction) → `registerAsync()` 등록
- `AgentHttpServer.handleAgentAction()`: `registry.isAsync()` 분기 후 `createAsync()` 호출
- `SequenceAction`: `createFreshAsyncAction()` 제거 → `ActionRegistry.createAsync()` 통합

**`memory`** (I-018)
- `AgentHttpServer.handleAgentRoute()`: `/memory/*` 5개 케이스 추가 (create/get/update/delete/search)
- `handleAgentMemoryCreate()` 신규: body에 scope/visible_to 없으면 자동으로 `agent:{name}` 스코핑 + `visibleTo=[name]`
- `handleAgentMemorySearch()` 신규: 기본 scope `agent:{name}` (global + 본인 메모리)

**`infra`** (I-019)
- `MemoryManager.reload()` 신규: entries 클리어 → load() 재호출
- `AgentHttpServer`: `POST /memory/reload` 엔드포인트 추가

**`brain`** (I-024)
- `ObservationBuilder.buildNearbyBlocks()`: blockstate properties 포함. `BlockState.getValues()` 비어있지 않은 블록에 `state` 객체 추가 (age, type, facing, lit, open 등)
- stone/dirt 등 properties 없는 블록은 state 필드 생략 → 토큰 효율 유지

### 설계 판단

- **Action 팩토리 패턴**: async action만 팩토리 대상. sync action은 stateless이므로 공유 인스턴스 유지. `SequenceAction.createFreshAsyncAction()`와 동일한 리플렉션 방식을 `ActionRegistry`로 중앙화
- **Memory 자동 스코핑**: MCP→HTTP 경로에서 agent name이 URL path에 이미 있으므로 (bridgeFetch의 agentPrefix), Java 쪽 라우팅만 추가하면 TypeScript 변경 불필요
- **Memory 핫 리로드**: 파일 워처 대신 명시적 `POST /memory/reload` — 간단하고 예측 가능

### 해결 이슈

- [x] `I-017`: Action 싱글톤 공유 → async action 팩토리 패턴으로 에이전트별 독립 인스턴스
- [x] `I-018`: Memory 에이전트 스코핑 → per-agent HTTP 라우팅 + 자동 visibleTo 설정
- [x] `I-019`: Memory 핫 리로드 → `MemoryManager.reload()` + HTTP 엔드포인트
- [x] `I-024`: observation blockstate 추가 → nearby_blocks에 state properties 포함 (age, type, facing 등)

### 테스트 결과

[v0.3.2+ / 2026-03-13 실행 기록](runs/v0.3.2/2026-03-13.md) 참조.

- I-017: 빌드+단일 에이전트 정상. 동시 실행 테스트 미완
- I-018: per-agent memory 생성 확인 (m003=zim, m004=alex)
- I-019: 코드 포함, 실행 검증 필요

### 발견 이슈

- [ ] `I-020`: 이미 경작된 farmland에 호미 재사용 — use_item_on_area가 블록 상태 필터링 없음 (80건 PASS 낭비)
- [ ] `I-021`: use_item_on_area item=empty 시 조기 중단 없음 — I-014 재확인, 씨앗 소진 후 81블록 전체 순회
- [ ] `I-022`: brain이 `place_block water` 호출 — water_bucket + use_item_on 패턴 프롬프트 가이드 필요
- [ ] `I-023`: execute_sequence 내에서 move_to 없이 먼 블록에 use_item_on 시도 (dist 5.7 > max 4.5)
- [ ] `I-024`: **[enhancement]** observation nearby_blocks에 blockstate 누락 — block_id만 반환, age/type 등 properties 없음. 밀 성장 단계(age=0~7) 구분 불가 → 미성숙 밀 수확, 상자 더블/싱글 구분 불가 → 불필요한 open 시도. agent(alex)가 직접 지적

---

## v0.5.1 — 2026-03-14 `[memory, schedule, brain, infra]`

Memory 데이터 모델 재설계 — 카테고리별 서브클래스, Location 단순화, Schedule content/config 분리, @reference 강제 로드, GUI mention 피커.

### 변경 사항

**`memory`**
- `MemoryEntry` base 정리: `tags`, `location`, `scope` 필드 제거. base는 id/title/desc/content/category/visibleTo/timestamps만 보유
- `preference` 카테고리 삭제 — 카테고리: storage, facility, area, event, skill, schedule
- `MemoryLocation` → interface로 변환: `distanceTo()`, `isWithinRange()`, `getType()`
- `PointLocation` 신규: `(x, y, z)` — radius 제거, 미사용 필드 JSON 노이즈 해결 (I-016)
- `AreaLocation` 신규: `(x1, y1, z1, x2, y2, z2)` — 단일 y 대신 y1/y2
- `Locatable` interface: `getLocation()` — 위치 있는 카테고리 구분
- 카테고리별 서브클래스 6개:
  - `StorageMemory` — PointLocation 필수, Locatable
  - `FacilityMemory` — MemoryLocation (point 또는 area), Locatable
  - `AreaMemory` — AreaLocation 필수, Locatable
  - `EventMemory` — MemoryLocation 선택, Locatable
  - `SkillMemory` — 추가 필드 없음
  - `ScheduleMemory` — ScheduleConfig 일급 객체 필드, content = 프롬프트 메시지
- `MemoryEntryTypeAdapter`: category 기반 다형성 역직렬화
  - serialize/deserialize에서 **모든 base 필드를 수동으로 처리** (`fillBaseFields`) — Gson reflection에 부모 클래스 필드를 맡기지 않음
  - old scope→visibleTo 마이그레이션, old schedule content JSON→config 변환
- `MemoryLocationTypeAdapter`: type 기반 dispatch, old format 호환 (radius 무시, 단일 y → y1=y2)
- `MemoryManager` 리팩토링:
  - Gson에 TypeAdapter 등록 (MemoryEntry, MemoryLocation, ScheduleConfig)
  - `createFromJson(JsonObject)` 팩토리 — 카테고리별 서브클래스 자동 생성
  - `@memory:mXXX` 참조 재귀 탐색 (`resolveReferences`, depth 3, 순환 방지)
  - `instanceof Locatable` 패턴으로 위치 접근
  - JSON 루트 v2 포맷 `{"version":2,"entries":[...]}`, v1 bare array 자동 감지 마이그레이션
  - preference auto-load 로직 제거

**`schedule`**
- `ScheduleEntry.java` 삭제 — `ScheduleMemory`로 대체
- `ScheduleConfig`: `promptMessage` 필드 제거 — 프롬프트는 `ScheduleMemory.content`에 저장
  - `@SerializedName` + `alternate` 추가 (target_agent, time_of_day 등 snake_case/camelCase 호환)
- `ScheduleConfigTypeAdapter` 신규: Gson reflection 대신 기존 수동 `toJson()/fromJson()` 강제 사용
- `ObserverDef`: `@SerializedName(value="event", alternate={"eventType"})` — 키 이름 통일
- `ScheduleManager`: `ScheduleEntry` → `ScheduleMemory` 직접 사용
  - `create(title, message, config, tick)` — message를 content에 저장
  - `trigger()`: `sm.getContent()`에서 프롬프트 읽기
  - `toSummaryJson()`: description + content(prompt) 포함
  - null eventType 방어 로직 추가
- `ObserverManager`: `ScheduleEntry` → `ScheduleMemory` 전환, null eventType skip + warn

**`brain`**
- `mcp-server.ts`:
  - `remember`: `tags` 파라미터 제거, `preference` 카테고리 제거, location에 y1/y2 추가, `@memory:mXXX` 참조 안내
  - `update_memory`: `tags` 파라미터 제거
  - `search_memory`: 검색 대상 "title and description"
- `manager-mcp-server.ts`: `tags`/`preference` 제거, location y1/y2 추가
- `prompt.ts`: preference 언급 제거, `@memory:mXXX` 참조 auto-load 안내

**`infra`**
- `AgentHttpServer`:
  - `handleMemoryCreate`: `createFromJson(body)` 단일 호출
  - schedule 핸들러: `ScheduleMemory` 직접 사용
- `GuiLayout.java` 신규: 공용 레이아웃 상수 (`LABEL_GAP=12`, `ROW_H=36`, `FIELD_H=16` 등)
- `MemoryEditScreen` 전면 재작성:
  - tags 입력 제거
  - 카테고리에 따라 location 표시/숨김 (skill → location 숨김)
  - Location: "none" 제거, Point/Area 사이클 버튼
  - Point: X, Y, Z (3필드), Area: X1/Y1/Z1 + X2/Y2/Z2 (6필드 2행)
  - `@` mention 피커: 트리거 키로 검색 팝업, ↑↓/Enter 선택, `@[Title]` 표시 ↔ `@memory:mXXX` 저장
  - `GuiLayout` 상수 기반 레이아웃
- `MemoryListScreen`: preference 카테고리 제거

### 설계 판단

- **Gson reflection 배제**: Gson reflection이 Java 모듈 시스템 환경(Forge 1.20.1)에서 부모 클래스 private 필드 접근 실패, 필드명↔JSON 키 불일치 문제 유발. `MemoryEntryTypeAdapter`에서 base 필드를 **전부 수동으로 읽고 씀** (`fillBaseFields`). `ScheduleConfigTypeAdapter`로 config도 수동 `toJson()/fromJson()` 강제. 서브클래스 고유 필드만 Gson reflection 허용
- **Schedule content = 프롬프트**: `ScheduleConfig.promptMessage` 삭제. `ScheduleMemory.content`가 에이전트에게 보내는 메시지, `description`이 사람용 설명, `config`는 순수 트리거 메커니즘
- **@reference 강제 로드**: content에 `@memory:m001` → auto_loaded에 강제 포함. depth 3 재귀, `loadedIds` Set으로 순환 방지
- **@[Title] 표시 형식**: GUI에서 `@[Title]`로 표시하되 저장 시 `@memory:mXXX`로 변환. 대괄호가 경계 구분자 역할 — 공백 포함 제목도 파싱 가능
- **Location 단순화**: radius 제거 (사용처 없었음), area에 y1/y2 추가 (3D 볼륨)

### 해결 이슈

- [x] `I-016`: MemoryLocation area 타입에서 미사용 필드(x, z, radius)가 0으로 직렬화 — PointLocation/AreaLocation 분리
- [x] `I-017`: Gson reflection이 ObserverDef.eventType을 "eventType" 키로 직렬화 → 수동 toJson의 "event" 키와 불일치 — `ScheduleConfigTypeAdapter` + `@SerializedName`
- [x] `I-018`: Gson reflection이 부모 클래스(MemoryEntry) private 필드 접근 실패 → content 소실 — `fillBaseFields()` 수동 처리

### 파일 변경 요약

| 구분 | 파일 |
|------|------|
| 신규 (14) | `PointLocation`, `AreaLocation`, `Locatable`, `MemoryLocationTypeAdapter`, `MemoryEntryTypeAdapter`, `StorageMemory`, `FacilityMemory`, `AreaMemory`, `EventMemory`, `SkillMemory`, `ScheduleMemory`, `ScheduleConfigTypeAdapter`, `GuiLayout` |
| 수정 (11) | `MemoryEntry`, `MemoryLocation`, `MemoryManager`, `ScheduleConfig`, `ScheduleManager`, `ObserverManager`, `ObserverDef`, `AgentHttpServer`, `MemoryEditScreen`, `MemoryListScreen`, `CLAUDE.md` |
| MCP 수정 (3) | `mcp-server.ts`, `manager-mcp-server.ts`, `prompt.ts` |
| 삭제 (1) | `ScheduleEntry` |

---

## v0.5.0 — 2026-03-13 `[schedule, brain, infra]`

스케줄 시스템 + Agent Manager — 시간/간격/옵저버 기반 자동 작업 트리거, 보디리스 매니저 런타임.

### 변경 사항

**`schedule`**
- `ScheduleEntry.java` 신규: MemoryEntry 래퍼, category="schedule", config JSON을 content에 직렬화
- `ScheduleConfig.java` 신규: 트리거 설정 (TYPE: TIME_OF_DAY, INTERVAL, OBSERVER)
  - TIME_OF_DAY: 게임 시각(0-23999) + repeatDays
  - INTERVAL: N틱마다 반복 + repeat 플래그
  - OBSERVER: 옵저버 목록 + threshold
- `ObserverDef.java` 신규: 개별 옵저버 (위치 + 이벤트 타입 + 조건)
- `ScheduleManager.java` 신규: 싱글턴, CRUD + 틱 평가 + 트리거 라우팅
  - `init()`: MemoryManager에서 schedule 엔트리 로드 → 캐시
  - `tick()`: TIME_OF_DAY/INTERVAL 매 틱 체크
  - `trigger()`: Manager 런타임 경유 또는 직접 에이전트 intervention
- `ObserverManager.java` 신규: Forge 이벤트 핸들러, 옵저버 레지스트리
  - 지원 이벤트: crop_grow, sapling_grow, block_break, block_place, baby_spawn, entity_death, explosion
  - 조건 매칭: `age=7`, `type=zombie` 등 BlockState 속성 비교
  - threshold 기반 트리거: N개 옵저버 충족 시 ScheduleManager에 알림
- `ManagerContext.java` 신규: Manager 런타임용 보디리스 컨텍스트 (InterventionQueue, sessionId)

**`brain`**
- `manager-index.ts` 신규: Agent Manager SDK 루프 (Claude Agent SDK, maxTurns=50)
- `manager-mcp-server.ts` 신규: Manager MCP 도구
  - Schedule CRUD: create_schedule, update_schedule, delete_schedule, list_schedules, get_schedule
  - Observer 관리: add_observers, remove_observers, list_observers
  - Agent 관리: list_agents, spawn_agent, despawn_agent, tell_agent, get_agent_status
  - Memory (global): search_memory, remember, recall
  - Game state: get_world_time, get_supported_events
- `manager-prompt.ts` 신규: Manager 시스템 프롬프트

**`infra`**
- `ManagerRuntimeManager.java` 신규: Manager 런타임 프로세스 관리 (launch/stop)
- `ManagerCommand.java` 신규: `/am <message>` — Agent Manager에게 메시지 전달
- `AgentHttpServer`: Schedule/Observer/Manager 엔드포인트 추가
  - `/schedule/create`, `/schedule/update`, `/schedule/delete`, `/schedule/list`, `/schedule/get`
  - `/observer/add`, `/observer/remove`, `/observer/list`
  - `/manager/intervention`, `/manager/world_time`, `/manager/events`
- `AgentMod`: 서버 시작 시 `ScheduleManager.init()`, 틱에서 `ScheduleManager.tick()` 호출
- `AgentTickHandler`: ScheduleManager 틱 평가 연동

### 설계 판단

- **Schedule = Memory**: 스케줄을 MemoryEntry(category="schedule")로 통합. 별도 저장소 불필요, MemoryManager의 CRUD/persist/visibleTo 재사용
- **Agent Manager = 보디리스**: FakePlayer 없음, 행동 도구 없음. 스케줄 등록/관리 + 에이전트에게 메시지 전달만 담당
- **Observer = Forge 이벤트**: 월드에 블록/엔티티를 배치하지 않고 Forge 이벤트 구독으로 감지. InvisibleObserver 패턴
- **트리거 라우팅**: Manager 런타임이 있으면 intervention 큐로 전달, 없으면 직접 에이전트 런타임 launch

### 파일 변경 요약

| 구분 | 파일 |
|------|------|
| 신규 (10) | `ScheduleEntry`, `ScheduleConfig`, `ObserverDef`, `ScheduleManager`, `ObserverManager`, `ManagerContext`, `ManagerRuntimeManager`, `ManagerCommand`, `manager-index.ts`, `manager-mcp-server.ts`, `manager-prompt.ts` |
| 수정 (5) | `AgentMod`, `AgentTickHandler`, `AgentHttpServer`, `MemoryManager`, `CLAUDE.md` |

---

## v0.3.2 — 2026-03-13 `[multi-agent]`

명령어 구조 변경 — `/agent <subcommand> <name>` → `/agent <name> <subcommand|message>`. 에이전트 지정을 앞에 두고, 메시지는 subcommand 없이 직접 전달.

### 변경 사항

**`multi-agent`**
- `AgentCommand.java` 전면 재작성: `greedyString()` + 수동 파싱 방식으로 `@name` 패턴 구현
  - `/agent @alex spawn`, `/agent @alex 나무 캐와` (tell 키워드 제거)
  - `/agent list`는 글로벌 (에이전트 지정 불필요)
  - `SUGGEST_INPUT`: `@` 입력 시 에이전트 이름 제안, 이름 선택 후 subcommand 제안
  - `dispatch()`: `@name` 파싱 → subcommand 매칭 (spawn/despawn/status/stop/pause/resume) → 미매칭 시 tell
- `CLAUDE.md` 명령어 문서 업데이트

### 설계 판단

- `word()`는 `@` 문자 미지원 → terminal-mod와 동일하게 `greedyString()` + 수동 파싱 채택
- `@name rest` 구조를 dispatch()에서 switch로 subcommand/message 분기
- 탭 완성 2단계: `@` 입력 시 에이전트 이름 + 이름 확정 후 subcommand 제안
- 디스크 에이전트 폴더도 탭 완성에 포함하여, 아직 스폰되지 않은 에이전트도 `spawn` 시 이름 자동완성 가능

---

## v0.3.0 — 2026-03-13 `[brain, actions, body, multi-agent, memory, infra]`

멀티 에이전트 시스템 — 이름 기반 복수 FakePlayer 동시 운영, PERSONA.md 역할/도구 분리, 메모리 스코핑, 관리 GUI.

### 변경 사항

**Phase 1: Multi FakePlayer + 독립 명령**

**`body`**
- `AgentContext.java` 신규: 에이전트별 상태 번들 (FakePlayer, ActionManager, InterventionQueue, PersonaConfig, 런타임 프로세스, 세션ID)
- `AgentManager.java` 신규: `ConcurrentHashMap<String, AgentContext>` 기반 멀티 에이전트 관리. `FakePlayerManager` 대체
- `AgentTickHandler`: 단일 에이전트 → `AgentManager.getAllAgents()` 순회
- `AgentAnimation`: AgentManager 참조로 전환

**`actions`**
- `ActiveActionManager`: 싱글턴 제거 → 에이전트별 인스턴스
- `MineBlockAction`, `MineAreaAction`: `cachedAgent` 패턴으로 cancel() 시 참조 유지

**`infra`**
- `AgentHttpServer`: Per-agent 라우팅 (`/agent/{name}/observation`, `/agent/{name}/action`, `/agent/{name}/intervention`, `/agent/{name}/status`)
- `RuntimeManager`: 싱글 프로세스 → AgentContext 기반, `AGENT_NAME` env var 추가
- `InterventionQueue`: 싱글턴 제거 → 에이전트별 인스턴스
- `AgentCommand`: 전면 재작성 — `/agent spawn <name>`, `/agent despawn <name>`, `/agent tell <name> <msg>`, `/agent list`, `/agent status`, `/agent stop`, `/agent pause`, `/agent resume`. 탭 완성 지원

**Phase 2: Persona System**

**`brain`**
- `PersonaConfig.java` 신규: `.agent/agents/{name}/PERSONA.md` 파서 (`## Role`, `## Personality`, `## Tools`)
- `prompt.ts`: 정적 `SYSTEM_PROMPT` → `buildSystemPrompt()` 동적 생성. 페르소나 내용 + 에이전트 이름 주입
- `mcp-server.ts`: `AGENT_TOOLS` env var 기반 도구 필터링. `isAllowed()` 게이트로 조건부 등록 (15개 도구). `get_observation`, 메모리 도구, `execute_sequence`는 항상 등록
- `intervention.ts`, `index.ts`: per-agent URL 프리픽스 적용

**`infra`**
- `RuntimeManager`: `AGENT_PERSONA_CONTENT`, `AGENT_TOOLS` env var 추가 전달
- `AgentManager.spawn()`: PERSONA.md 자동 로드 → PersonaConfig 생성

**Phase 3: Memory Scoping**

**`infra`**
- `MemoryManager`: 전면 재작성. `globalEntries` ← `.agent/memory.json`, `agentEntries: Map<String, List>` ← `.agent/agents/{name}/memory.json`
  - `loadAgent(name)`, `unloadAgent(name)`, `saveAgent(name)`, `saveAll()`
  - `search(query, category, scope)` — scope-aware 검색
  - `searchAll()` — 전체 스코프 검색
- `AgentHttpServer`: `/agent/{name}/memory/*` → scope=`agent:name`, `/memory/search` scope 파라미터 지원
- `ObservationBuilder`: `build(agent, level, agentName)` — 글로벌+에이전트 메모리 병합

**Phase 4: Agent Management GUI**

**`infra`**
- `AgentManagementScreen.java` 신규: 좌측 에이전트 목록 (스폰 상태 표시) + 우측 페르소나 편집 (역할/성격/도구 체크박스)
- `BridgeClient`: `get()` 메서드 추가
- `ClientSetup`: G키 바인딩 추가
- `AgentHttpServer`: `GET /agents/list` (디렉토리 스캔 + 스폰 상태), `GET/POST /agent/{name}/persona`, `POST /agents/delete`

**Phase 5: Memory & Agent GUI Overhaul**

**`infra`**
- `DropdownWidget.java` 신규: Minecraft 1.20.1 커스텀 드롭다운 위젯 (Z=400 오버레이 렌더링, 항목 색상/구분선, 외부 클릭 닫기)
- `MemoryListScreen`: 전면 재작성 — 카테고리 드롭다운 + 에이전트 스코프 드롭다운 + 검색, scope badge에 에이전트 이름 전체 나열, ID 표시 제거
- `MemoryEditScreen`: 전면 재작성 — 2컬럼 레이아웃 (좌: Title/Desc/Content, 우: Category 드롭다운/Tags/Visibility/Location), 에이전트 선택 오버레이 팝업 (Z=400), 선택된 에이전트 이름 나열, m:n visibility (visible_to), 하단 버튼 고정
- `AgentManagementScreen`: 전면 재작성 — 3컬럼 (좌: 에이전트 목록+검색, 중: Name/Role/Personality, 우: Tools 체크리스트), 수평+수직 중앙 정렬, 이름 편집/rename 지원

**Acquaintance System (지인)**

**`brain`**
- `PersonaConfig`: `Acquaintance` record (name, description) + `## Acquaintances` 섹션 파싱 (`- name: description` 형식)
- `ObservationBuilder`: `acquaintances` 섹션 Observation에 주입 — name, description, spawned 여부, distance, position
- 향후 협업/채팅 기능의 기반 — 에이전트가 누구와 소통 가능한지 인지

**`infra`**
- `AgentHttpServer`: persona GET/POST에 acquaintances 배열 추가, PERSONA.md `## Acquaintances` 섹션으로 직렬화
- `AgentManagementScreen`: 지인 목록 렌더링 + [x] 제거 + 추가 입력 (name + description + [+] 버튼)

### 설계 판단

- **싱글턴 → 인스턴스 패턴**: ActiveActionManager, InterventionQueue를 AgentContext가 각자 보유. 에이전트 간 액션/개입 완전 격리
- **도구 필터링 = 에이전트 정체성**: MCP tool list 자체가 에이전트의 인가이자 역할. PERSONA.md의 `## Tools`로 정의, despawn→respawn으로 적용
- **메모리 스코핑**: 글로벌(상자 위치 등) + 에이전트별(개인 작업 큐 등) 분리. Observation에는 병합 주입
- **GUI 우선**: 코드/파일 편집 없이 인게임에서 에이전트 생성/삭제/페르소나 편집/메모리 관리 가능
- **지인 = skill-like**: name+description으로 정의, observation에 주입. 모든 에이전트가 서로 아는 것이 아니라 PERSONA.md에 명시된 관계만 인지. 향후 협업/채팅 시 "누구에게 요청할 수 있는지" 판단 근거

### 파일 변경 요약

| 구분 | 파일 |
|------|------|
| 신규 (6) | `AgentContext`, `AgentManager`, `PersonaConfig` (with Acquaintance), `AgentManagementScreen`, `DropdownWidget`, `BridgeClient.get()` |
| 전면 재작성 (6) | `AgentCommand`, `AgentHttpServer`, `MemoryManager`, `MemoryListScreen`, `MemoryEditScreen`, `mcp-server.ts` |
| 주요 수정 (13) | `ActiveActionManager`, `InterventionQueue`, `AgentTickHandler`, `RuntimeManager`, `AgentMod`, `AgentAnimation`, `MineBlockAction`, `MineAreaAction`, `ObservationBuilder` (acquaintances 주입), `prompt.ts`, `intervention.ts`, `index.ts` |
| GUI 수정 (2) | `ClientSetup`, `AgentManagementScreen` |
| 미사용 잔존 (1) | `FakePlayerManager` — 삭제 대상 |

---

## v0.2.5 — 2026-03-12 `[brain, infra]`

통합 메모리 시스템 — Brain이 장소, 시설, 절차적 지식을 세션 간 유지. GUI 편집기 + Observation 자동 주입.

### 변경 사항

**`brain`**
- MCP 도구 5개 추가: `remember`, `update_memory`, `forget`, `recall`, `search_memory`
- `mcp-server.ts`: bridgeFetch → `/memory/*` 엔드포인트 연결
- 시스템 프롬프트 Memory system 섹션 추가:
  ```
  - Observations include a "memories" section with title_index + auto_loaded
  - Use recall(id) to read full content of a memory in title_index
  - Use remember() to save important locations, facilities, resources, preferences
  - Use update_memory() when information changes
  - Always remember: storage, facilities, areas, events, preferences, skills
  - When arriving at known location, check if memory is still accurate
  ```
- `ObservationBuilder`: `memories` 섹션 추가 — `title_index` (전체 거리순) + `auto_loaded` (카테고리별 content 주입)

**`infra`**
- `MemoryEntry`: 통합 데이터 모델 (id, title, description, content, category, tags, location, scope, timestamps)
- `MemoryLocation`: point/area 위치 모델 — distanceTo(), isWithinRange()
- `MemoryManager`: 싱글턴 CRUD + JSON persistence (atomic write) + CopyOnWriteArrayList (스레드 안전)
  - Auto-load 규칙: preference=항상, storage/facility/area=32블록 이내, event=최신 5개
  - ID: 순차 `m001`, `m002` (ergonomic)
  - 저장: `.agent/memory.json`
- `AgentHttpServer`: `/memory/create`, `/memory/get`, `/memory/update`, `/memory/delete`, `/memory/search` 엔드포인트
- `AgentMod`: 서버 시작 시 `MemoryManager.load()`, 종료 시 `save()`
- GUI: `M` 키바인드 → `MemoryListScreen` (카테고리 탭 + 검색 + 거리순 리스트) + `MemoryEditScreen` (편집/생성/삭제)
- `BridgeClient`: GUI → HTTP bridge 비동기 통신
- `AreaMarkHandler`: 월드에서 블록 2개 우클릭으로 영역 코너 지정

### 설계 판단

Claude Code의 skill 시스템과 동일 메커니즘 채용: title+description 항상 로드 → 카테고리별 auto-load 규칙으로 content 주입.
Brain이 스스로 `remember` 호출하여 지식 축적, 이후 세션에서 observation 통해 자동 recall.
GUI는 플레이어가 직접 메모리 편집/검증할 수 있도록 Screen 기반 구현.

### 테스트 결과

[v0.2.5 / 2026-03-12 실행 기록](runs/v0.2.5/2026-03-12.md) 참조.

- Brain이 `remember` 자발 호출: 상자(m001, storage), 농장(m002, area) 기억 생성
- `update_memory`로 상자 내용 갱신 확인
- memory.json 저장/로드 정상 동작
- auto-load (loadedAt 갱신) 확인

### 발견 이슈

- [ ] `I-014`: 씨앗 부족 감지 미비 — 64개로 81칸 심기 시도, item=empty 후에도 계속 순회. 조기 중단 필요
- [ ] `I-015`: move_to y좌표 추정 실패 — y=63 반복 시도 후 y=65에서 성공. 지형 높이 자동 보정 또는 observation 활용 필요
- [ ] `I-016`: MemoryLocation area 타입에서 미사용 필드(x, z, radius)가 0으로 직렬화 — JSON 노이즈
- [ ] `I-017`: **[critical]** Action 싱글톤 공유 — AsyncAction 인스턴스가 ActionRegistry에서 1개만 존재, 모든 에이전트가 공유. mutable 필드(pathFollower, future, cachedAgent 등) 덮어쓰기로 에이전트 간 행동 간섭 (alex 명령 → zim 실행). v0.3.0 "싱글턴 → 인스턴스" 마이그레이션에서 ActiveActionManager만 분리하고 Action 객체 자체는 누락
- [ ] `I-018`: **[critical]** Memory 에이전트 스코핑 누락 — MCP remember()가 request body에 agent name/scope/visible_to 미포함, HTTP 서버도 URL path에서 agent name 미추출. 결과: 모든 메모리가 scope="global"로 저장되어 에이전트별 메모리 격리 실패
- [ ] `I-019`: Memory 핫 리로드 미지원 — MemoryManager가 서버 시작 시 1회 load() 후 RAM 캐시만 사용. GUI 편집은 RAM+디스크 동시 반영되나, 디스크 직접 편집(memory.json) 시 에이전트에 반영 안 됨. reload 엔드포인트/메커니즘 없음

---

## v0.2.4 — 2026-03-12 `[brain, infra]`

세션 컨텍스트 유지 — 같은 서버 세션 내 명령 간 대화 히스토리 resume.

### 변경 사항

**`brain`**
- `index.ts`: 첫 명령은 `sessionId`로 세션 생성 + 전체 시스템 프롬프트. 이후 명령은 `resume: sessionId`로 이전 대화 이어가기 + 명령만 전달
- SDK `persistSession` (기본 true) 활용 — 트랜스크립트 자동 저장, 별도 구현 불필요

**`infra`**
- `RuntimeManager`: 서버 시작 시 UUID `sessionId` 생성, 모든 명령에 `AGENT_SESSION_ID` + `AGENT_IS_RESUME` 환경변수 전달
- `RuntimeManager.resetSession()`: 서버 종료 시 세션 초기화 — 새 서버 = 새 세션
- `AgentMod.onServerStopping()`: `resetSession()` 호출 추가

### 설계 판단

기존: 매 명령마다 `query()` 새 호출 → brain이 이전 작업 컨텍스트 상실.
변경: SDK의 `resume` 옵션으로 동일 세션 내 대화 히스토리 자동 이어가기.
Brain이 "아까 상자에서 꺼낸 아이템", "방금 만든 농장" 같은 맥락을 기억한 채로 후속 명령 수행 가능.

---

## v0.2.3 — 2026-03-12 `[actions, brain, infra]`

컨테이너 조작 개선 (슬롯 반환 + click_slot 피드백 강화) + 채팅 메시지 줄바꿈 + 프롬프트 가이드.

### 변경 사항

**`actions`**
- `OpenContainerAction`: 열린 컨테이너의 모든 슬롯 내용물을 `slots` 배열로 반환 (I-008)
- `ClickSlotAction` 피드백 강화: before/after 슬롯 상태 + carried 아이템 반환
  - 파라미터명 다형성: `click_action`, `action_type`, `action` 순서로 읽기 (execute_sequence 경유 대응)
  - `action` 필드 우선순위 최하위로 변경 — execute_sequence에서 `action`이 액션 이름(`"click_slot"`)과 충돌하는 버그 수정 (I-013)
- `ContainerTransferAction` 신규 (내부 전용): ActionRegistry에만 등록, MCP 미노출

**`brain`**
- MCP `click_slot` 설명 강화: before/after 상태 반환 명시, execute_sequence 연계 안내
- MCP `container_transfer` 제거 — 모델이 사전 학습된 click_slot 패턴을 선호하므로 모델 prior와 일치하는 방향으로 전환
- `open_container` 설명 업데이트: 슬롯 내용물 반환 명시
- 시스템 프롬프트 가이드: open→review slots→click_slot(quick_move) + execute_sequence 패턴

**`infra`**
- `RuntimeManager.relayToChat()`: `\n` 줄바꿈 처리 + 줄별 전송 (최대 20줄), 300자 truncate 제거 (I-009)

### 설계 판단

container_transfer를 MCP에 노출했으나 brain이 완전 무시하고 click_slot을 환각으로 호출.
SDK가 MCP 미등록 도구 호출을 차단하지 않아 execute_sequence 경유로도 click_slot 사용.
**결론**: 모델의 사전 학습 prior (click_slot 패턴)와 싸우지 말고, click_slot 자체를 강화하여 모델 prior를 활용.

### 해결 이슈

- [x] `I-008`: open_container 슬롯 반환 + click_slot 피드백 강화 (before/after/carried)
- [x] `I-009`: relayToChat 줄바꿈 처리 — 긴 메시지를 줄별로 분리 전송
- [x] `I-010`: 모델 prior 활용 전략으로 전환 — click_slot 유지하여 환각 호출 방지
- [x] `I-013`: click_slot `action` 필드 우선순위 충돌 — execute_sequence에서 quick_move가 pickup으로 실행되는 버그 수정

### 발견 이슈

- [ ] `I-011`: execute_sequence가 MCP 미등록 action도 호출 가능 — ActionRegistry 직접 접근. 현재는 문제 없으나 향후 action 제거 시 주의 필요
- [ ] `I-012`: 화로 제련 대기 — open/close 폴링 (10회 이상). 제련 완료 이벤트 또는 대기 메커니즘 필요

---

## v0.2.2 — 2026-03-12 `[actions, body]`

환경 피드백 raw fact 보고 + 이동 시 시선 수평화.

### 변경 사항

**`actions`**
- `UseItemOnAreaAction`: 실패 시 `failure_details` 배열 추가 — pos, block, above, result, dist (raw fact)
- `UseItemOnAreaAction`: 대상 블록 위 블록 정보(`above`) substep 로그에 추가
- `UseItemOnAreaAction`: 경로 실패(no_path, no_standing_position)도 failure_details에 포함

**`body`**
- `MoveToAction`: lookAt Y를 `waypoint.getY() + 0.5` → `agent.getEyeY()` (시선 수평화, I-006)
- `UseItemOnAreaAction`: 이동 중 시선도 동일하게 수평화

### 테스트 결과

[v0.2.2 / 2026-03-12 실행 기록](runs/v0.2.2/2026-03-12.md) 참조.

- 경작 성공률: **100%** (v0.2.1 48% → v0.2.2 100%)
- Brain이 `failure_details`의 `above=grass` 정보로 풀 제거 후 재경작 자동 수행
- 38턴, $0.69, 3분 25초

### 해결 이슈

- [x] `I-005`/`I-007`: 환경 제약 raw fact 보고 — brain이 failure_details로 실패 원인 추론 가능
- [x] `I-006`: 이동 시 시선 수평화 — 진행 방향 정면 응시

### 발견 이슈

- [ ] `I-008`: 상자 아이템 추출 비효율 — open/close 3회 반복, brain이 한번에 파악 못함
- [ ] `I-009`: 채팅 결과 보고 메시지 잘림 — 긴 메시지 truncate + `\n` 리터럴 표시
- [ ] `I-010`: 제거된 도구(pickup_items) 호출 시도 — 프롬프트 또는 에러 메시지 개선 필요

---

## v0.2.1 — 2026-03-12 `[actions, brain, body, infra]`

EquipAction 버그 수정 + 프롬프트 도구 가이드 + 채팅 줄바꿈 + 머리 회전 패킷 + pickup_items 제거.

### 변경 사항

**`actions`**
- `EquipAction`: 아이템 1개만 장착 → 전체 스택 장착 (I-002 수정)
- `PickupItemsAction` 제거 (legacy) — 2블록 자동흡수로 대체

**`brain`**
- 시스템 프롬프트에 Tool usage guide 섹션 추가:
  ```
  - Use mine_area instead of calling mine_block repeatedly
  - Items within 2 blocks are auto-collected every tick
  - Use execute_sequence to chain multiple actions in one call
  - Use use_item_on_area for farming/planting area operations
  - craft supports count parameter for batch crafting
  ```
- `pickup_items` MCP 도구 제거

**`body`**
- `AgentAnimation.lookAt()`: `ClientboundRotateHeadPacket` 추가 (머리 회전 동기화)

**`infra`**
- `ChatMonitor`: 60자 기준 줄바꿈 + word-wrap (채팅 잘림 수정)

### 테스트 결과

[v0.2.1 / 2026-03-12 실행 기록](runs/v0.2.1/2026-03-12.md) 참조.

### 발견 이슈

- [x] `I-002`: equip 전체 스택 장착으로 수정 완료
- [x] `I-004`: pickup_items 제거 — 자동흡수로 대체
- [ ] `I-005`: use_item_on_area에서 grass_block에 호미 PASS 반환 — 잔디(tall_grass) 위의 grass_block은 경작 불가, 잔디 제거 필요
- [ ] `I-006`: MoveToAction lookAt이 다음 waypoint(발밑)를 바라봄 → 땅 응시하며 걷기 (정면 응시 필요)
- [ ] `I-007`: 9×9 영역에 water/잔디 혼재 시 use_item_on_area 성공률 48% — 블록 필터링 또는 결과 보고 후 brain이 재시도 필요
- [ ] `I-001`: 부분 해결 — equip 수정으로 아이템 소진 문제 해결, 그러나 PASS 반환 자체는 별도 원인 (I-005)

---

## v0.2.0 — 2026-03-11 `[actions, brain, body, infra]`

행동 시퀀스 + 영역 기반 액션 + 애니메이션 + 실제 제작.

### 변경 사항

**`actions`**
- `CraftAction` 전면 재작성: 레시피 조회 전용 → 실제 재료 소비 + 결과물 생성, `count` 파라미터
- `MineAreaAction` 신규: 영역 채굴 (걷기+채굴 상태 머신, 최대 256블록)
- `UseItemOnAreaAction` 신규: 2D 영역 우클릭 (서펜타인 패턴)
- `SequenceAction` 신규: 액션 배열 순차 실행, sync 체이닝
- 기존 액션에 `AgentAnimation.lookAt()` + `swingArm()` 추가

**`brain`**
- `maxTurns`: 20 → 50
- MCP 도구 추가: `mine_area`, `use_item_on_area`, `execute_sequence`
- `craft` 도구에 `count` 파라미터 추가

**`body`**
- `AgentAnimation` 유틸리티 신규: lookAt (yaw/pitch), swingArm 패킷 브로드캐스트
- `AgentTickHandler`: 매 틱 2블록 반경 아이템 자동 흡수

**`infra`**
- `AsyncAction.getTimeoutMs()`: 액션별 동적 타임아웃 (하드코딩 60초 제거)
- `PathFollower.getCurrentTarget()`: 현재 웨이포인트 조회
- `AgentHttpServer`: 동적 타임아웃 적용

### 테스트 결과

[v0.2.0 / 2026-03-11 실행 기록](runs/v0.2.0/2026-03-11.md) 참조.

### 발견 이슈

- `I-001`: use_item_on_area 거리/위치 계산 → 부분 실패 후 개별 재시도
- `I-002`: equip 매번 재호출 필요 — mainhand 아이템 유실 의심
- `I-003`: mine_block x3 사용 — mine_area 미활용 (brain 문제)
- `I-004`: pickup_items 수동 호출 — 자동흡수 인지 못함 (brain 문제)

---

## v0.1.0 — 2026-03-11 `[actions, brain, body, infra]`

Phase 0~4 초기 구현. AsyncAction 인프라, useItemOn, 걷기, 틱 채굴.

### 변경 사항

**`brain`**
- 시스템 프롬프트 (`prompt.ts`):
  ```
  You are a Minecraft agent controlling a player character in the world.
  - Always call get_observation first
  - Plan your actions step by step before executing
  - You are invulnerable and don't need food
  - Available tools will be provided by the MCP server.
  ```
- `maxTurns`: 20
- MCP 도구 22개: get_observation, move_to, mine_block, place_block, pickup_items, drop_item, equip, attack, interact, open_container, click_slot, close_container, craft, smelt, use_item_on, remember_location, remember_facility, remember_preference, recall

**`actions`**
- 15개 기본 액션 구현 (move_to, mine_block, place_block, equip, attack 등)
- craft/smelt은 레시피 조회만 (실제 제작 불가)

**`body`**
- FakePlayer 스폰/디스폰 + 클라이언트 가시성 패킷

**`infra`**
- HTTP 브릿지 (localhost:0, 자동 포트)
- A* 패스파인딩 + 틱 기반 이동
- AsyncAction 인프라 (ActiveActionManager, 60초 하드코딩 타임아웃)
- Claude Agent SDK 런타임 + MCP 서버
- 모니터링 (채팅, 터미널 연동)

### 테스트 결과

정식 테스트 미실시. craft가 레시피 조회만 수행, 실제 제작 불가 확인.
