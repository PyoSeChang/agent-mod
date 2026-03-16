# Test Cases — agent-mod

> 생성일: 2026-03-15
> Phase 1 전수 조사 + Phase 2 리뷰 결과 종합

## 개요

- Total: ~210 test cases
- Java Unit: ~140 cases (17 test classes)
- Java Integration: ~30 cases (4 test classes)
- TypeScript Unit: ~34 cases (7 test files)
- TypeScript Integration: ~10 cases (2 test files)

## 우선순위 정의

- **P1**: Critical — 서버 크래시, 데이터 손실 방지. 릴리스 블로커
- **P2**: High — 기능 버그 포착. 다음 버전 전 작성
- **P3**: Medium — 커버리지 확보. 안정화 단계
- **P4**: Low — Nice-to-have. 여유 시 작성
- **P5**: Future — 인프라 필요 (Minecraft mock, 테스트 프레임워크)

## 사전 요구사항

### Java

- JUnit 5 의존성 추가 (`build.gradle`)
- `src/test/java` 디렉토리 구조
- ForgeGradle test classpath에 `BlockPos` 등 Minecraft 클래스 포함 확인
  - **Flag**: TC-093, TC-108~114, A17 전체 — `BlockPos` 의존, classpath 검증 필요
- 싱글톤 리셋 전략: reflection 기반 필드 초기화 또는 test-only `reset()` 메서드
  - **Flag**: Section B 전체 — `MemoryManager`, `ScheduleManager`, `ObserverManager` 싱글톤 리셋 전략 사전 정의 필수

### TypeScript

- Vitest 설정 (ESM 호환)
- 모듈 private 함수 extract 또는 re-export for testing
  - **Flag**: C5/C6/C7 — `bridgeFetch`, entrypoint 로직이 private. 테스트 위해 리팩토링 또는 re-export 필요

---

## A. Java Unit Tests

### A1. PersonaConfigTest

**Target**: `core/PersonaConfig.java`
**Prerequisites**: None (package-private `parseContent` is testable)
**Setup**: Call `PersonaConfig.parseContent(name, content)` with markdown strings

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-001 | Parse complete PERSONA.md with all sections | `"## Role\nFarmer\n\n## Personality\nFriendly\n\n## Tools\n- move_to\n- mine_block\n\n## Acquaintances\n- steve: farming partner"` | role="Farmer", personality="Friendly", tools=["move_to","mine_block"], acquaintances=[("steve","farming partner")] | P1 |
| TC-002 | Parse with empty tools section (all tools allowed) | `"## Role\nBuilder\n\n## Personality\nCreative\n\n## Tools\n"` | tools=[], isAllToolsAllowed()=true | P1 |
| TC-003 | Parse with missing sections | `"## Role\nMiner"` | role="Miner", personality="", tools=[], acquaintances=[] | P2 |
| TC-004 | Parse completely empty content | `""` | role="", personality="", tools=[], acquaintances=[] | P2 |
| TC-005 | Parse with reversed section order | `"## Tools\n- craft\n\n## Role\nSmith\n\n## Personality\nStoic"` | All sections parsed correctly regardless of order | P2 |
| TC-006 | Acquaintance with colon in description | `"## Acquaintances\n- alex: farmer: handles wheat and carrots"` | name="alex", description="farmer: handles wheat and carrots" (first colon splits) | P1 |
| TC-007 | Acquaintance without description | `"## Acquaintances\n- alex"` | name="alex", description="" | P3 |
| TC-008 | Tools with asterisk bullet | `"## Tools\n* move_to\n* mine_block"` | tools=["move_to","mine_block"] | P3 |
| TC-009 | Section header case insensitivity | `"## ROLE\nTest\n\n## role\nTest2"` | Parsed via toLowerCase() — last "role" section wins | P3 |
| TC-010 | defaultPersona() returns correct defaults | Call `defaultPersona("testAgent")` | name="testAgent", role="General-purpose agent", tools=[], isAllToolsAllowed()=true | P2 |
| TC-011 | getToolsCsv() with tools | Config with tools=["move_to","craft"] | Returns "move_to,craft" | P2 |
| TC-012 | getToolsCsv() with no tools | Config with tools=[] | Returns "" | P2 |
| TC-013 | Parse multiline role content | `"## Role\nLine 1\nLine 2\nLine 3\n\n## Personality\nNice"` | role="Line 1\nLine 2\nLine 3" | P3 |
| TC-014 | Parse tools with extra whitespace | `"## Tools\n-   move_to  \n-  craft  "` | tools=["move_to","craft"] (trimmed) | P3 |
| TC-015 | Parse ignores non-h2 headers | `"# Title\n## Role\nMiner\n### Sub-heading\ndetail"` | role="Miner\n### Sub-heading\ndetail" — only ## triggers section split | P3 |

### A2. MemoryEntryTest

**Target**: `core/memory/MemoryEntry.java`
**Prerequisites**: None (POJO)
**Setup**: `new MemoryEntry()`, set fields manually

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-016 | getScope() for global entry | visibleTo=[] | "global" | P1 |
| TC-017 | getScope() for single-agent entry | visibleTo=["alex"] | "agent:alex" | P1 |
| TC-018 | getScope() for multi-agent entry | visibleTo=["alex","steve"] | "agents:alex,steve" | P1 |
| TC-019 | isVisibleTo() for global entry | visibleTo=[], check isVisibleTo("anyone") | true | P1 |
| TC-020 | isVisibleTo() for scoped entry (match) | visibleTo=["alex"], check isVisibleTo("alex") | true | P1 |
| TC-021 | isVisibleTo() for scoped entry (no match) | visibleTo=["alex"], check isVisibleTo("steve") | false | P1 |
| TC-022 | isGlobal() with null visibleTo | Set visibleTo to null via setVisibleTo(null) | isGlobal()=true, getVisibleTo() returns empty list (not null) | P2 |
| TC-023 | matchesQuery() with matching title | title="Wheat Farm", query="wheat" | true | P1 |
| TC-024 | matchesQuery() with matching description | description="Storage for iron ingots", query="iron" | true | P1 |
| TC-025 | matchesQuery() case insensitive | title="Diamond Mine", query="DIAMOND" | true | P2 |
| TC-026 | matchesQuery() with null query | query=null | true (matches all) | P2 |
| TC-027 | matchesQuery() with blank query | query="   " | true (matches all) | P2 |
| TC-028 | matchesQuery() no match | title="Farm", description="crops", query="diamond" | false | P2 |
| TC-029 | matchesQuery() with null title and description | title=null, description=null, query="test" | false | P3 |
| TC-030 | markUpdated() sets updatedAt | Call markUpdated() | updatedAt is a valid ISO instant, different from original | P3 |
| TC-031 | markLoaded() sets loadedAt | Call markLoaded() | loadedAt is a valid ISO instant | P3 |
| TC-032 | Constructor sets timestamps | new MemoryEntry() | createdAt and updatedAt are non-null ISO instants | P3 |
| TC-033 | setVisibleTo(null) creates empty list | setVisibleTo(null) | getVisibleTo() returns empty list, not null | P2 |
| TC-034-N | matchesQuery() does NOT search content field | title="Farm", description="crops", content="diamond ore location", query="diamond" | false (content is not searched) | P2 |

### A3. PointLocationTest

**Target**: `core/memory/PointLocation.java`
**Prerequisites**: None (pure math)

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-035 | distanceTo() at same point | Point(10,64,10), distanceTo(10,64,10) | 0.0 | P1 |
| TC-036 | distanceTo() along one axis | Point(0,0,0), distanceTo(3,0,4) | 5.0 (3-4-5 triangle) | P1 |
| TC-037 | distanceTo() 3D diagonal | Point(0,0,0), distanceTo(1,1,1) | sqrt(3) ~= 1.732 | P2 |
| TC-038 | isWithinRange() inside | Point(0,64,0), isWithinRange(3,64,0, 5.0) | true (dist=3, range=5) | P1 |
| TC-039 | isWithinRange() exactly on boundary | Point(0,0,0), isWithinRange(5,0,0, 5.0) | true (dist=range, uses <=) | P2 |
| TC-040 | isWithinRange() outside | Point(0,0,0), isWithinRange(10,0,0, 5.0) | false | P2 |
| TC-041 | getType() returns "point" | new PointLocation() | "point" | P3 |
| TC-042 | Negative coordinates | Point(-100, -64, -200), distanceTo(100, 64, 200) | correct Euclidean distance | P3 |

### A4. AreaLocationTest

**Target**: `core/memory/AreaLocation.java`
**Prerequisites**: None (pure math)

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-043 | Coordinate normalization (swapped corners) | AreaLocation(10,70,10, 0,60,0) | x1=0,y1=60,z1=0, x2=10,y2=70,z2=10 | P1 |
| TC-044 | distanceTo() inside area | Area(0,60,0, 10,70,10), distanceTo(5,65,5) | 0.0 (point is inside) | P1 |
| TC-045 | distanceTo() outside on one axis | Area(0,60,0, 10,70,10), distanceTo(15,65,5) | 5.0 (closest point is edge at x=10) | P1 |
| TC-046 | distanceTo() outside on corner | Area(0,0,0, 10,10,10), distanceTo(13,0,14) | sqrt(9+16)=5.0 (dist to corner 10,0,10) | P2 |
| TC-047 | isWithinRange() inside area (range 0) | Area(0,0,0, 10,10,10), isWithinRange(5,5,5, 0) | true (dist=0 since inside) | P2 |
| TC-048 | isWithinRange() outside with sufficient range | Area(0,0,0, 10,10,10), isWithinRange(20,5,5, 15) | true | P2 |
| TC-049 | getType() returns "area" | new AreaLocation() | "area" | P3 |
| TC-050 | Default constructor | new AreaLocation() | All coordinates are 0.0 | P4 |
| TC-051 | Already-normalized coordinates pass through | AreaLocation(0,0,0, 10,10,10) | x1=0,x2=10 (unchanged) | P4 |

### A5. MemoryEntryTypeAdapterTest

**Target**: `core/memory/MemoryEntryTypeAdapter.java`
**Prerequisites**: Gson instance with registered adapters
**Setup**: Create Gson with `MemoryEntryTypeAdapter`, `MemoryLocationTypeAdapter`, `ScheduleConfigTypeAdapter`

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-052 | Deserialize StorageMemory | JSON: `{"category":"storage","id":"m001","title":"Chest","location":{"type":"point","x":10,"y":64,"z":20}}` | StorageMemory instance, location is PointLocation(10,64,20) | P1 |
| TC-053 | Deserialize FacilityMemory with AreaLocation | JSON: `{"category":"facility","title":"Farm","location":{"type":"area","x1":0,"y1":64,"z1":0,"x2":10,"y2":64,"z2":10}}` | FacilityMemory with AreaLocation | P1 |
| TC-054 | Deserialize AreaMemory | JSON: `{"category":"area","title":"Mine Zone","location":{"type":"area","x1":0,"y1":0,"z1":0,"x2":5,"y2":5,"z2":5}}` | AreaMemory instance | P2 |
| TC-055 | Deserialize EventMemory (no location) | JSON: `{"category":"event","title":"Found diamonds"}` | EventMemory with location=null | P2 |
| TC-056 | Deserialize SkillMemory | JSON: `{"category":"skill","title":"How to farm wheat"}` | SkillMemory instance | P2 |
| TC-057 | Deserialize ScheduleMemory with config | JSON: `{"category":"schedule","title":"Daily harvest","config":{"type":"TIME_OF_DAY","time_of_day":6000,"target_agent":"alex"}}` | ScheduleMemory with valid config | P1 |
| TC-058 | Deserialize unknown category | JSON: `{"category":"unknown","title":"Test"}` | Base MemoryEntry (default case) | P2 |
| TC-059 | Deserialize null category | JSON: `{"title":"No category"}` | Base MemoryEntry with null category | P2 |
| TC-060 | Round-trip: serialize then deserialize StorageMemory | Create StorageMemory, serialize, deserialize | All fields preserved including location | P1 |
| TC-061 | Round-trip: serialize then deserialize ScheduleMemory | Create ScheduleMemory with TIME_OF_DAY config | Config fields preserved after round-trip | P1 |
| TC-062 | Backward compat: scope migration "agent:alex" | JSON: `{"category":"event","scope":"agent:alex","visibleTo":[]}` | visibleTo=["alex"] after migration | P1 |
| TC-063 | Backward compat: scope migration "agents:alex,steve" | JSON: `{"category":"event","scope":"agents:alex,steve","visibleTo":[]}` | visibleTo=["alex","steve"] | P2 |
| TC-064 | Backward compat: scope "global" does not migrate | JSON: `{"category":"event","scope":"global","visibleTo":[]}` | visibleTo remains [] | P2 |
| TC-065 | Backward compat: ScheduleMemory old format (config in content) | JSON: `{"category":"schedule","content":"{\"type\":\"TIME_OF_DAY\",\"time_of_day\":6000,\"target_agent\":\"alex\",\"prompt_message\":\"harvest\"}"}` | config parsed from content, content set to "harvest" | P1 |
| TC-066 | Serialize includes base fields | StorageMemory with all fields set | JSON has id, title, description, content, category, scope, visibleTo, createdAt, updatedAt | P2 |
| TC-067 | Serialize includes location for Locatable | StorageMemory with PointLocation | JSON has "location" at top level | P2 |
| TC-068 | Non-JSON input throws JsonParseException | String "not json" | JsonParseException | P3 |

### A6. MemoryLocationTypeAdapterTest

**Target**: `core/memory/MemoryLocationTypeAdapter.java`
**Prerequisites**: Gson with adapter

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-069 | Deserialize point location | JSON: `{"type":"point","x":10,"y":64,"z":20}` | PointLocation(10,64,20) | P1 |
| TC-070 | Deserialize area with y1/y2 | JSON: `{"type":"area","x1":0,"z1":0,"x2":10,"z2":10,"y1":60,"y2":70}` | AreaLocation with y1=60, y2=70 | P1 |
| TC-071 | Backward compat: area with single "y" | JSON: `{"type":"area","x1":0,"z1":0,"x2":10,"z2":10,"y":64}` | AreaLocation with y1=64, y2=64 | P1 |
| TC-072 | Backward compat: point ignores old "radius" | JSON: `{"type":"point","x":10,"y":64,"z":20,"radius":5}` | PointLocation(10,64,20) — radius ignored | P2 |
| TC-073 | Missing type defaults to "point" | JSON: `{"x":10,"y":64,"z":20}` | PointLocation(10,64,20) | P2 |
| TC-074 | Missing coordinate fields default to 0 | JSON: `{"type":"point"}` | PointLocation(0,0,0) | P3 |
| TC-075 | Round-trip point | Create PointLocation, serialize, deserialize | Coordinates preserved | P2 |
| TC-076 | Round-trip area | Create AreaLocation, serialize, deserialize | All 6 coordinates preserved | P2 |

### A7. ScheduleConfigTest

**Target**: `core/schedule/ScheduleConfig.java`
**Prerequisites**: None (POJO + JSON manual serialization)

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-077 | toJson/fromJson round-trip TIME_OF_DAY | Config: type=TIME_OF_DAY, timeOfDay=6000, targetAgent="alex", repeatDays=1 | All fields preserved | P1 |
| TC-078 | toJson/fromJson round-trip INTERVAL | Config: type=INTERVAL, intervalTicks=1200, repeat=true, targetAgent="steve" | All fields preserved | P1 |
| TC-079 | toJson/fromJson round-trip OBSERVER | Config: type=OBSERVER, observers=[ObserverDef(0,70,0,"crop_grow","age=7")], threshold=3 | Observers and threshold preserved | P1 |
| TC-080 | Default field values | new ScheduleConfig() | enabled=true, repeatDays=1, repeat=true, threshold=1, lastTriggeredTick=-1, lastTriggeredDay=-1 | P2 |
| TC-081 | fromJson with missing optional fields | JSON: `{"type":"TIME_OF_DAY","target_agent":"alex"}` | timeOfDay=0, repeatDays=1, enabled=true (defaults) | P2 |
| TC-082 | fromJson with invalid type | JSON: `{"type":"INVALID"}` | IllegalArgumentException from Type.valueOf() | P1 |
| TC-083 | fromJson with missing type | JSON: `{"target_agent":"alex"}` | NullPointerException on obj.get("type") | P1 |
| TC-084 | Boundary: timeOfDay=0 (sunrise) | toJson/fromJson with timeOfDay=0 | Preserved as 0 | P3 |
| TC-085 | Boundary: timeOfDay=23999 (max) | toJson/fromJson with timeOfDay=23999 | Preserved as 23999 | P3 |
| TC-086 | No validation: timeOfDay negative | fromJson with timeOfDay=-1 | Accepted without error (documents missing validation) | P3 |
| TC-087 | No validation: timeOfDay > 23999 | fromJson with timeOfDay=30000 | Accepted without error (documents missing validation) | P3 |
| TC-088 | No validation: intervalTicks=0 | fromJson with intervalTicks=0 | Accepted without error (would cause division by zero in tick check) | P1 |

### A8. ObserverDefTest

**Target**: `core/schedule/ObserverDef.java`
**Prerequisites**: Forge BlockPos class (available in ForgeGradle test classpath)
**Flag**: BlockPos dependency — needs ForgeGradle classpath validation

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-089 | toJson/fromJson round-trip | ObserverDef(10,70,20,"crop_grow","age=7") | All fields preserved | P1 |
| TC-090 | toJson omits null condition | ObserverDef(0,0,0,"block_break",null) | JSON has no "condition" key | P2 |
| TC-091 | toJson omits empty condition | ObserverDef(0,0,0,"block_break","") | JSON has no "condition" key | P2 |
| TC-092 | fromJson with missing "event" key | JSON: `{"x":0,"y":0,"z":0}` | eventType="" (has() check present) | P2 |
| TC-093 | fromJson with missing coordinate | JSON: `{"y":5,"z":10,"event":"crop_grow"}` | NPE on obj.get("x").getAsInt() — missing has() check | P1 |
| TC-094 | getBlockPos() conversion | ObserverDef(10,70,20,"crop_grow",null) | BlockPos(10,70,20) | P3 |

### A9. ActionRegistryTest

**Target**: `core/action/ActionRegistry.java`
**Prerequisites**: Singleton — need to test in isolation. Create test-only Action/AsyncAction implementations.
**Setup**: Reset singleton (or use fresh instance if testable). Register mock actions.

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-095 | Register and get sync action | Register mock Action("test_sync"), get("test_sync") | Returns same instance | P1 |
| TC-096 | Register async action class | Register mock AsyncAction class, createAsync("test_async") | Returns new instance each call | P1 |
| TC-097 | isAsync() for sync action | Register sync "test_sync" | isAsync("test_sync")=false | P2 |
| TC-098 | isAsync() for async action | Register async "test_async" | isAsync("test_async")=true | P2 |
| TC-099 | get() for unknown action | get("nonexistent") | null | P2 |
| TC-100 | createAsync() for unknown action | createAsync("nonexistent") | null | P2 |
| TC-101 | listNames() returns all registered | Register sync "a", async "b" | listNames() contains both "a" and "b" | P3 |
| TC-102 | get() for async action creates fresh instance | Register async, get("test_async") twice | Two distinct instances (!=) | P2 |
| TC-103 | Duplicate registration does not duplicate in names list | Register sync "test" twice | listNames() contains "test" once | P3 |

### A10. InterventionQueueTest

**Target**: `monitor/InterventionQueue.java`
**Prerequisites**: None (wrapper around ConcurrentLinkedQueue)
**Note**: Tests JDK stdlib. Keep one smoke test at P3, rest at P4.

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-104 | Empty queue poll returns null | new queue, poll() | null | P3 |
| TC-105 | Add and poll FIFO order | add("a"), add("b"), poll(), poll() | "a", "b" | P4 |
| TC-106 | hasMessages() on empty | new queue | false | P4 |
| TC-107 | hasMessages() after add | add("msg") | true | P4 |
| TC-108 | clear() empties queue | add("a"), add("b"), clear() | hasMessages()=false, poll()=null | P4 |

### A11. PathFollowerTest

**Target**: `core/pathfinding/PathFollower.java`
**Prerequisites**: Needs ServerPlayer mock for tick() — but `start()`, `isFinished()`, `cancel()`, `getPath()`, `getCurrentIndex()`, `getCurrentTarget()` are testable without mock.
**Setup**: Create PathFollower, use `start()` with BlockPos lists
**Flag**: BlockPos dependency — needs ForgeGradle classpath validation

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-109 | start() with valid path activates | start([BlockPos(0,64,0), BlockPos(5,64,0)]) | isFinished()=false, currentIndex=0 | P2 |
| TC-110 | start() with null path | start(null) | isFinished()=true | P2 |
| TC-111 | start() with empty path | start([]) | isFinished()=true | P2 |
| TC-112 | getCurrentTarget() returns first waypoint | start([BlockPos(5,64,5)]) | getCurrentTarget()=BlockPos(5,64,5) | P3 |
| TC-113 | getCurrentTarget() when not active | No start() called | null | P3 |
| TC-114 | cancel() stops and resets | start() then cancel() | isFinished()=true, getPath() is empty, getCurrentIndex()=0 | P2 |
| TC-115 | getPath() returns the path | start(path) | getPath() == path | P3 |

### A12. ManagerContextTest

**Target**: `core/schedule/ManagerContext.java`
**Prerequisites**: None (POJO)

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-116 | Initial state | new ManagerContext() | sessionId != null, hasLaunched()=false, runtimeProcess=null, isRuntimeRunning()=false | P2 |
| TC-117 | resetSession() changes sessionId | Get old sessionId, resetSession() | new sessionId != old, hasLaunched()=false | P2 |
| TC-118 | isRuntimeRunning() with null process | runtimeProcess=null | false | P2 |
| TC-119 | interventionQueue is initialized | new ManagerContext() | getInterventionQueue() != null | P3 |

### A13. ScheduleManagerBuildDescriptionTest

**Target**: `core/schedule/ScheduleManager.buildDescription()` (package-private static)
**Prerequisites**: None

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-120 | Short message | Config(TIME_OF_DAY, "alex"), message="harvest wheat" | "Schedule: TIME_OF_DAY -> alex \| harvest wheat" | P3 |
| TC-121 | Long message truncated | Config, message=60 chars | First 50 chars + "..." | P3 |
| TC-122 | Null message | Config, null message | Contains "Schedule: TYPE -> agent \| " | P3 |

### A14. ScheduleConfigTypeAdapterTest

**Target**: `core/schedule/ScheduleConfigTypeAdapter.java`
**Prerequisites**: Gson with adapter

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-123 | Non-object input throws | JSON string | JsonParseException | P3 |

> TC-115, TC-116 (delegation tests) removed per review: pure delegation to `toJson()`/`fromJson()`, no independent value.

### A15. ActiveActionManagerTest (NEW)

**Target**: `core/action/ActiveActionManager.java`
**Prerequisites**: Mock `AsyncAction` implementation (stub `start()`, `tick()`, `cancel()`, `isActive()`, `getName()`)
**Setup**: Create `ActiveActionManager` instance, inject mock AsyncActions

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-124 | start() stores action and returns future | Create mock AsyncAction, call startAction(action, null, params) | getCurrentAction() == action, returned future matches action.start() result | P1 |
| TC-125 | start() cancels previous active action | Start action A (isActive=true), then start action B | action A cancel() called, getCurrentAction() == B | P1 |
| TC-126 | cancel() stops current action | Start action, then cancel() | getCurrentAction() == null | P1 |
| TC-127 | getCurrentAction() returns null initially | new ActiveActionManager() | getCurrentAction() == null | P2 |
| TC-128 | tick() delegates to current action | Start active action, call tick(null) | action.tick() called once | P1 |

### A16. AgentContextTest (NEW)

**Target**: `core/AgentContext.java`
**Prerequisites**: ServerPlayer mock (can be null for basic state tests), PersonaConfig
**Setup**: `new AgentContext(name, null, persona)` for state tests

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-129 | Initial state has non-null components | new AgentContext("test", null, persona) | sessionId != null, actionManager != null, interventionQueue != null, hasLaunched()=false, isStoppedByUser()=false | P2 |
| TC-130 | resetSession() changes sessionId | Get old sessionId, call resetSession() | new sessionId != old, hasLaunched()=false | P2 |
| TC-131 | isRuntimeRunning() with null process | runtimeProcess not set | isRuntimeRunning()=false | P2 |
| TC-132 | sessionId uniqueness across instances | Create 2 AgentContext instances | sessionId values differ | P2 |

### A17. PathfinderTest (NEW)

**Target**: `core/pathfinding/Pathfinder.java`
**Prerequisites**: Mock `ServerLevel` for walkability checks (`getBlockState()`, `BlockState.isAir()`, `BlockState.isSolid()`)
**Setup**: Create mock ServerLevel with configurable block grid. Stub `getBlockState(pos)` to return air/solid based on test layout.
**Flag**: BlockPos dependency — needs ForgeGradle classpath validation

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-133 | Same-position path returns single node | findPath(level, pos(5,64,5), pos(5,64,5), 100) | [BlockPos(5,64,5)] (singletonList) | P1 |
| TC-134 | Path around obstacle | Flat ground with 1-block wall between from and to | Path list > direct distance, avoids wall blocks | P1 |
| TC-135 | Unreachable target returns empty list | Target fully enclosed by solid blocks | Collections.emptyList() | P1 |
| TC-136 | MAX_SEARCH_NODES cap | Large open area, target at distance > 1000 nodes | Returns empty (search exhausted before reaching target) | P2 |
| TC-137 | Step-up walkability | Ground at y=64, adjacent ground at y=65, air above both | Path includes step-up from y=64 to y=65 | P1 |
| TC-138 | Step-down walkability | Ground at y=65, adjacent ground at y=64, air at transition | Path includes step-down from y=65 to y=64 | P1 |

---

## B. Java Integration Tests

### B1. MemoryManagerTest

**Target**: `core/memory/MemoryManager.java`
**Prerequisites**: Singleton reset, temp directory for file I/O, mock `FMLPaths`
**Setup**: Reflection to reset INSTANCE or extract testable methods. Create temp `.agent/` directory structure.
**Flag**: FMLPaths dependency — needs explicit mock strategy (reflection to set `GAMEDIR` or inject temp path)

Split into 4 sub-groups for maintainability:

#### B1a. MemoryManagerCrudTest

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-139 | create() assigns sequential IDs | Create 3 entries | IDs are "m001", "m002", "m003" | P1 |
| TC-140 | get() returns created entry | Create entry, get(id) | Same entry | P1 |
| TC-141 | get() returns null for unknown ID | get("m999") | null | P2 |
| TC-142 | delete() removes entry | Create, delete(id) | get(id)=null, size decremented | P1 |
| TC-143 | delete() returns false for unknown ID | delete("m999") | false | P2 |
| TC-144 | update() modifies fields | Create entry, update with new title | get(id).getTitle() = new title, updatedAt changed | P1 |
| TC-145 | update() preserves unmodified fields | Create entry with title+desc, update only title | description unchanged | P2 |
| TC-146 | createFromJson() with category dispatch | JSON: `{"category":"storage","title":"Chest","location":{"type":"point","x":0,"y":64,"z":0}}` | StorageMemory instance with PointLocation | P1 |
| TC-147 | createFromJson() defaults category to "event" | JSON without category | entry.getCategory() = "event" | P2 |
| TC-148 | update() with visible_to snake_case field | Create entry, update with `{"visible_to":["alex","steve"]}` | entry.getVisibleTo() == ["alex","steve"] | P2 |

#### B1b. MemoryManagerSearchTest

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-149 | search() by query | Create entries with titles "Farm" and "Mine", search("farm") | Returns only "Farm" entry | P1 |
| TC-150 | search() by category | Create storage and event entries, search("", "storage") | Returns only storage entries | P1 |
| TC-151 | search() by scope "global" | Create global and scoped entries, search("","","global") | Only global entries | P1 |
| TC-152 | search() by scope "agent:alex" | Create global + alex-visible + steve-only entries | Returns global + alex entries (not steve-only) | P1 |
| TC-153 | search() by scope "only:alex" | Same setup as above | Returns only alex entries (excludes global) | P2 |
| TC-154 | getMergedEntries(null) returns all | Create mixed entries | All entries returned | P2 |
| TC-155 | getMergedEntries("alex") filters by visibility | Create global + alex + steve entries | Returns global + alex only | P1 |
| TC-156 | getAllForTitleIndex() sorts by distance | Create entries at distances 100, 10, 50 | Sorted: 10, 50, 100 | P2 |
| TC-157 | getAllForTitleIndex() entries without location sort last | Mix of Locatable and non-Locatable entries | Non-locatable entries at end (dist=-1) | P3 |

#### B1c. MemoryManagerAutoLoadTest

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-158 | getAutoLoadContent() loads nearby storage | Create StorageMemory at (10,64,10), query from (10,64,12) | Included (within 32 blocks) | P1 |
| TC-159 | getAutoLoadContent() excludes distant storage | Create StorageMemory at (100,64,100), query from (0,64,0) | Excluded (>32 blocks) | P2 |
| TC-160 | getAutoLoadContent() loads latest 5 events | Create 7 events with different updatedAt | Only latest 5 returned | P2 |
| TC-161 | getAutoLoadContent() @reference resolution | Entry m001 content has "@memory:m002", m002 exists | Both m001 and m002 in result | P1 |
| TC-162 | @reference resolution depth limit | m001 refs m002, m002 refs m003, m003 refs m004 (depth=3 limit) | m001,m002,m003 loaded; m004 NOT loaded (depth exceeded) | P2 |
| TC-163 | @reference cycle safety | m001 refs m002, m002 refs m001 | Both loaded, no infinite loop | P1 |

#### B1d. MemoryManagerMigrationTest

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-164 | save/load round-trip v2 format | Create entries, save(), clear, load() | All entries restored with correct types | P1 |
| TC-165 | load() v1 format migration | Write bare JSON array to file, load() | Entries loaded, format upgraded on next save | P2 |
| TC-166 | ID counter recovery after load | Save entries with IDs m001..m010, reload | Next created ID is m011 | P2 |

### B2. ScheduleManagerTest

**Target**: `core/schedule/ScheduleManager.java`
**Prerequisites**: MemoryManager singleton (mock or temp file), ObserverManager singleton
**Setup**: Reset ScheduleManager, create test schedules via ScheduleManager.create()

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-167 | create() adds to cache and memory | create("Test", "msg", TIME_OF_DAY config, tick=0) | get(id) != null, MemoryManager.get(id) != null | P1 |
| TC-168 | delete() removes from cache | Create then delete | get(id)=null, MemoryManager.get(id)=null | P1 |
| TC-169 | list() filters by targetAgent | Create schedules for "alex" and "steve" | list("alex",false) returns only alex's | P2 |
| TC-170 | list() enabledOnly filter | Create enabled and disabled schedules | list(null,true) excludes disabled | P2 |
| TC-171 | tick() TIME_OF_DAY triggers at exact time | Schedule at timeOfDay=6000, tick(6000, day=0, serverTick=6000) | trigger() called | P1 |
| TC-172 | tick() TIME_OF_DAY does not re-trigger same day | Already triggered on day=5, tick again with same day | No re-trigger | P1 |
| TC-173 | tick() TIME_OF_DAY repeatDays=0 disables after first trigger | repeatDays=0, trigger once | config.isEnabled()=false | P2 |
| TC-174 | tick() TIME_OF_DAY repeatDays=2 skips odd days | repeatDays=2, triggered on day 0 | Day 1: no trigger. Day 2: trigger | P2 |
| TC-175 | tick() INTERVAL triggers at correct interval | intervalTicks=100, registeredTick=0 | Triggers at serverTick=100, 200, 300 | P1 |
| TC-176 | tick() INTERVAL repeat=false disables after first | repeat=false, trigger once | config.isEnabled()=false | P2 |
| TC-177 | tick() skips disabled schedules | Schedule with enabled=false | No trigger regardless of tick values | P2 |
| TC-178 | update() modifies schedule fields | Create schedule, update with new message | getContent() reflects new message | P2 |
| TC-179 | init() loads from MemoryManager | Pre-populate MemoryManager with ScheduleMemory entries, init() | Schedules loaded into cache | P2 |
| TC-180 | init() resets trigger state | Schedule with lastTriggeredTick=500, init() | lastTriggeredTick=-1, lastTriggeredDay=-1 | P2 |
| TC-181-N | Multi-schedule tick: one disables, others still tick | 3 schedules: A (repeatDays=0, triggers), B and C (repeat). Tick at trigger time. | A disabled after trigger; B and C still trigger on next matching tick | P2 |

### B3. AgentHttpServerRouteTest

**Target**: `network/AgentHttpServer.java`
**Prerequisites**: Running HTTP server instance, mock AgentManager/MemoryManager
**Setup**: Start server on random port, use java.net.http.HttpClient
**Note**: P5 because requires full Minecraft mock for server thread delegation

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-182 | GET /actions returns action list | No setup | 200 + JSON array of action names | P5 |
| TC-183 | GET /agent/{name}/status for unknown agent | name="unknown" | 404 | P5 |
| TC-184 | POST /agent/{name}/action with unknown action | `{"action":"nonexistent"}` | 404 | P5 |
| TC-185 | GET /memory/search returns entries | Pre-populate memory | 200 + JSON array | P5 |
| TC-186 | POST /schedule/create creates schedule | Valid schedule JSON | 200 + schedule with ID | P5 |
| TC-187 | Agent route parsing: /agent/alex/observation | HTTP request | agentName="alex", subpath="/observation" | P5 |
| TC-188 | Agent route parsing: /agent/name-with-dash/status | HTTP request | agentName="name-with-dash" | P5 |
| TC-189 | Agent route with no subpath | GET /agent/alex | 400 "Missing sub-path" | P5 |

---

## C. TypeScript Unit Tests

### C1. prompt.test.ts

**Target**: `agent-runtime/src/prompt.ts`
**Prerequisites**: Mock process.env
**Setup**: Set AGENT_NAME and AGENT_PERSONA_CONTENT env vars before import

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-190 | buildSystemPrompt() includes base rules | No env vars | Contains "get_observation first", "mine_area instead of", "memory system" | P1 |
| TC-191 | buildSystemPrompt() appends persona when set | AGENT_PERSONA_CONTENT="## Role\nFarmer" | Contains "--- Your Identity ---" and "Farmer" | P1 |
| TC-192 | buildSystemPrompt() appends agent name | AGENT_NAME="alex" | Contains "Your name is alex" | P1 |
| TC-193 | buildSystemPrompt() default name is "Agent" | AGENT_NAME unset | Contains "Your name is Agent" | P2 |
| TC-194 | buildSystemPrompt() no persona section when empty | AGENT_PERSONA_CONTENT="" | Does not contain "--- Your Identity ---" | P2 |

> TC-179 (SYSTEM_PROMPT === buildSystemPrompt()) removed per review: tautological, both use the same function call.

### C2. intervention.test.ts

**Target**: `agent-runtime/src/intervention.ts`
**Prerequisites**: Mock fetch
**Setup**: Mock global fetch, set env vars

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-195 | checkIntervention() returns message when available | Mock fetch returns `{"message":"pause"}` | "pause" | P2 |
| TC-196 | checkIntervention() returns null when no message | Mock fetch returns `{}` | null | P2 |
| TC-197 | checkIntervention() returns null on HTTP error | Mock fetch returns 500 | null | P2 |
| TC-198 | checkIntervention() returns null on network error | Mock fetch throws | null | P2 |
| TC-199 | sendLog() POSTs correct body | Mock fetch, call sendLog("thought", "hello") | fetch called with method=POST, body contains type and message | P2 |
| TC-200 | sendLog() does not throw on failure | Mock fetch throws | No exception propagated | P3 |
| TC-201 | checkIntervention() uses correct agent prefix URL | AGENT_NAME="alex" | fetch URL is `${bridgeUrl}/agent/alex/intervention` | P1 |
| TC-202 | checkIntervention() no prefix when no agent name | AGENT_NAME="" | fetch URL is `${bridgeUrl}/intervention` | P2 |
| TC-203-N | sendLog() URL uses /log without agent prefix | AGENT_NAME="alex", call sendLog() | fetch URL is `${bridgeUrl}/log` (NOT `${bridgeUrl}/agent/alex/log`) | P2 |

### C3. mcp-tool-filter.test.ts

**Target**: `agent-runtime/src/mcp-server.ts` (isAllowed function behavior)
**Prerequisites**: Module reloading with different AGENT_TOOLS env
**Note**: The `isAllowed` function is module-private. Test indirectly via tool registration, or extract it.

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-204 | All tools registered when AGENT_TOOLS unset | AGENT_TOOLS undefined | All tools (move_to, mine_block, craft, etc.) registered | P1 |
| TC-205 | Only specified tools registered | AGENT_TOOLS="move_to,mine_block" | Only move_to and mine_block registered (plus always-registered: get_observation, execute_sequence, memory tools) | P1 |
| TC-206 | Always-registered tools present regardless of filter | AGENT_TOOLS="move_to" | get_observation, execute_sequence, remember, recall, search_memory, update_memory, forget always present | P1 |
| TC-207 | Empty AGENT_TOOLS (empty string) means all allowed | AGENT_TOOLS="" | Split produces empty set, but isAllowed checks `!allowedTools` which is falsy for empty Set — BUG: empty string creates Set with one empty element | P1 |
| TC-208 | Tool names trimmed | AGENT_TOOLS=" move_to , craft " | "move_to" and "craft" in set (trimmed) | P2 |

### C4. manager-prompt.test.ts

**Target**: `agent-runtime/src/manager-prompt.ts`
**Prerequisites**: None (exported constant)

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-209 | MANAGER_SYSTEM_PROMPT includes key instructions | Import | Contains "Agent Manager", "schedule", "TIME_OF_DAY", "INTERVAL", "OBSERVER" | P2 |
| TC-210 | MANAGER_SYSTEM_PROMPT includes coordinate rules | Import | Contains "y+1 from the farmland" (critical for observer correctness) | P2 |
| TC-211 | MANAGER_SYSTEM_PROMPT includes game tick reference | Import | Contains "0=sunrise", "6000=noon", "12000=sunset" | P3 |

### C5. bridgeFetch.test.ts

**Target**: `agent-runtime/src/mcp-server.ts` bridgeFetch function
**Note**: bridgeFetch is module-private. Would need to extract or test via tool invocation.
**Flag**: Requires TS refactoring to extract private function

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-212 | /actions endpoint has no agent prefix | Mock fetch, trigger tool that calls bridgeFetch("/actions") | URL is `${bridgeUrl}/actions` (no /agent/name prefix) | P2 |
| TC-213 | Other endpoints use agent prefix | Mock fetch, trigger bridgeFetch("/observation") with AGENT_NAME="alex" | URL is `${bridgeUrl}/agent/alex/observation` | P2 |
| TC-214 | POST body serialized as JSON | Mock fetch, trigger tool with params | fetch body is JSON string with Content-Type header | P3 |

### C6. index-entrypoint.test.ts

**Target**: `agent-runtime/src/index.ts` — env var validation
**Prerequisites**: Mock process.exit, mock query
**Flag**: Requires TS refactoring to extract entrypoint logic

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-215 | Missing AGENT_BRIDGE_PORT exits with code 1 | AGENT_BRIDGE_PORT undefined | process.exit(1) called | P2 |
| TC-216 | Missing AGENT_MESSAGE exits with code 1 | AGENT_MESSAGE undefined | process.exit(1) called | P2 |
| TC-217 | Missing AGENT_SESSION_ID exits with code 1 | AGENT_SESSION_ID undefined | process.exit(1) called | P2 |
| TC-218 | isResume=true sends resume prompt format | AGENT_IS_RESUME="true" | prompt starts with "[Command]\n" not SYSTEM_PROMPT | P2 |
| TC-219 | isResume=false sends full prompt | AGENT_IS_RESUME="false" | prompt starts with SYSTEM_PROMPT | P2 |

### C7. manager-index-entrypoint.test.ts

**Target**: `agent-runtime/src/manager-index.ts` — env var validation + duplicate code
**Flag**: Requires TS refactoring to extract entrypoint logic

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-220 | Missing MANAGER_BRIDGE_PORT exits | Unset | process.exit(1) | P2 |
| TC-221 | Missing MANAGER_MESSAGE exits | Unset | process.exit(1) | P2 |
| TC-222 | checkIntervention() uses /manager/intervention URL | Mock fetch | URL path is "/manager/intervention" (not "/agent/name/intervention") | P2 |
| TC-223 | Resume prompt format matches index.ts | isResume=true | "[Command]\n{message}" format | P3 |

---

## D. TypeScript Integration Tests

### D1. mock-bridge-coverage.test.ts (extend existing test-mock-integration.ts)

**Target**: `agent-runtime/tests/mock-bridge.ts` + `agent-runtime/src/mcp-server.ts`
**Prerequisites**: Running mock-bridge process
**Setup**: Start mock-bridge, configure env vars

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-224 | Mock bridge: /observation returns state | GET /observation after spawn | JSON with position, inventory, nearby_blocks, nearby_entities | P2 |
| TC-225 | Mock bridge: /action mine_block removes block | POST mine_block with iron_ore coords | Block removed from nearby_blocks, item added to inventory | P2 |
| TC-226 | Mock bridge: /action move_to updates position | POST move_to with new coords | position updated | P2 |
| TC-227 | Mock bridge: memory endpoints | POST remember, then recall | Stored and retrieved | P3 |
| TC-228 | Mock bridge: sequence action | POST execute_sequence | Executes steps in order | P3 |
| TC-229 | Mock bridge: /intervention returns queued message | Queue intervention, GET /intervention | Returns message then empty | P3 |

### D2. manager-mcp-tools.test.ts

**Target**: `agent-runtime/src/manager-mcp-server.ts`
**Prerequisites**: Mock HTTP server for manager bridge
**Setup**: Start mock server with /schedule/*, /observer/*, /agent/* routes

| ID | Behavior | Input/Setup | Expected | Priority |
|----|----------|-------------|----------|----------|
| TC-230 | create_schedule tool sends correct HTTP | Invoke via MCP | POST /schedule/create with all params | P3 |
| TC-231 | list_agents tool | Invoke via MCP | GET /agents/list | P3 |
| TC-232 | tell_agent tool sends to correct path | name="alex", message="harvest" | POST /agent/alex/tell with {message} | P3 |
| TC-233 | add_observers tool | Invoke with schedule_id and observers | POST /observer/add | P3 |

---

## Summary

| Category | Count | P1 | P2 | P3 | P4 | P5 |
|----------|-------|----|----|----|----|-----|
| A. Java Unit Tests | 138 (TC-001 to TC-138) | 33 | 50 | 32 | 5 | 0 |
| B. Java Integration Tests | 51 (TC-139 to TC-189) | 15 | 21 | 1 | 0 | 8 |
| C. TypeScript Unit Tests | 34 (TC-190 to TC-223) | 5 | 22 | 5 | 0 | 0 |
| D. TypeScript Integration Tests | 10 (TC-224 to TC-233) | 0 | 4 | 6 | 0 | 0 |
| **TOTAL** | **233** | **53** | **97** | **44** | **5** | **8** |

> 원본 195개에서: +20 (신규 A15/A16/A17 + 확장 TC) - 5 (TC-115/116 삭제, TC-179 삭제) + 기타 번호 재배치 = ~210 실질 케이스. ID 공간은 233까지 사용 (번호 재배치로 인한 gap 최소화).

---

## 구현 순서

### Phase 2A — Pure Java Unit Tests (즉시 가치)

1. **MemoryEntryTest** (TC-016~034-N) — core data model, scope/visibility 핵심
2. **PointLocationTest** + **AreaLocationTest** (TC-035~051) — distance math, 버그 시 memory auto-load 깨짐
3. **MemoryEntryTypeAdapterTest** (TC-052~068) — serialization = 버그 서식지
4. **MemoryLocationTypeAdapterTest** (TC-069~076) — backward compat
5. **PersonaConfigTest** (TC-001~015) — 파싱 edge case
6. **ScheduleConfigTest** + **ObserverDefTest** (TC-077~094) — missing validation 문서화
7. **ActiveActionManagerTest** (TC-124~128) — **NEW**: 액션 상태 머신 핵심
8. **PathfinderTest** (TC-133~138) — **NEW**: A* 정확성, 서버 부하 방지

### Phase 2B — Singleton Integration Tests

9. **MemoryManagerTest** (4 sub-groups: TC-139~166) — 가장 복잡한 싱글톤
10. **ScheduleManagerTest** (TC-167~181-N) — tick 평가 로직

### Phase 2C — TypeScript Unit Tests

11. **prompt.test.ts** (TC-190~194) — 작성 간단
12. **intervention.test.ts** (TC-195~203-N) — fetch mocking
13. **mcp-tool-filter.test.ts** (TC-204~208) — persona 필터링

### Phase 2D — Extended Integration

14. All remaining TC-xxx as infrastructure allows

---

## Critical Bug-Catching Tests (최우선 작성)

| TC | Bug | Risk | Priority |
|----|-----|------|----------|
| TC-006 | Acquaintance colon-in-name splits wrong | Persona corruption | P1 |
| TC-083 | fromJson missing type → NPE | Manager crash | P1 |
| TC-088 | intervalTicks=0 → division by zero | Server crash (tick loop hang) | P1 |
| TC-093 | ObserverDef.fromJson() missing has() → NPE | Schedule creation crash | P1 |
| TC-163 | @reference cycle → infinite recursion | Server crash | P1 |
| TC-065 | ScheduleMemory old format backward compat | Old saves broken after upgrade | P1 |
| TC-207 | Empty AGENT_TOOLS string → broken filter | Agent gets no persona-filtered tools | P1 |
| TC-062 | Scope migration v1→v2 | Old memories invisible to agents | P1 |
| TC-125 | ActiveActionManager start replaces current (NEW) | Double action execution | P1 |
| TC-135 | Pathfinder unreachable target returns empty (NEW) | Agent stuck forever (infinite search) | P1 |
