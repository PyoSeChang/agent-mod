# Codebase Survey — agent-mod

> 전수 조사 일자: 2026-03-15
> 조사 범위: Java 74개 + TypeScript 7개 소스 + 6개 테스트
> 기준: Forge 1.20.1, Java 17, Node.js + Claude Agent SDK

## 요약 통계

- Total files analyzed: 87 (74 Java + 7 TS source + 6 TS test)
- Critical issues: 7
- High issues: 6
- Medium issues: 7
- Unit testable: 25 files
- Integration testable: 12 files
- In-game only: 50 files

---

## 1. Entry Point (1 file)

### AgentMod.java
- **역할**: Forge `@Mod` 진입점. 서버 시작/종료 라이프사이클 관리
- **의존성**: [Minecraft 필수] MinecraftForge, FMLJavaModLoadingContext. 모든 핵심 매니저를 초기화: ActionRegistry, MemoryManager, ScheduleManager, AgentHttpServer, RuntimeManager, ManagerRuntimeManager, ChatMonitor, TerminalIntegration, CompatRegistry, AgentLogger
- **핵심 로직**:
  - `AgentMod()` — Forge 이벤트 버스 등록, 클라이언트 환경이면 ClientSetup 초기화
  - `onServerStarting()` — 전체 시스템 부트스트랩: 액션 등록, 모니터링 초기화, 호환 모드 등록, 메모리/스케줄 로드, 로거 시작, HTTP 서버 시작
  - `onServerStopping()` — 스케줄/메모리 저장, 매니저 프로세스 종료, 로거 종료, 런타임 전체 중지, HTTP 서버 중지, 전 에이전트 디스폰
  - `onServerStopped()` — 서버 참조 null 초기화
- **테스트 가능성**: In-Game Only. Forge 이벤트 시스템과 서버 라이프사이클에 완전 의존
- **테스트 가능한 로직**: 없음 (순수 배선 코드)
- **발견된 이슈**:
  - managerCtx를 `onServerStarting()`에서 로컬 변수로 생성하지만 `onServerStopping()`에서는 `ScheduleManager.getManagerContext()`로 다시 가져옴 — 일관성은 있으나 null일 경우 NPE 가능
  - ActionRegistry 등 싱글톤이 서버 재시작 시 상태 리셋 없음 — 중복 등록 가능성
  - httpServer 포트 충돌 시 에러 처리 없음 (start()에서 예외 발생하면 RuntimeManager에 null 전파)

---

## 2. core/ 루트 (8 files)

### AgentPlayer.java
- **역할**: ServerPlayer 서브클래스. 클라이언트 없는 AI 플레이어의 틱 체인 수동 실행
- **의존성**: [Minecraft 필수] ServerPlayer, LivingEntity, MinecraftServer, ServerLevel
- **핵심 로직**:
  - `tick()` — super.tick(ServerPlayer) 호출 후, Player.tick()/LivingEntity.tick() 체인을 수동 실행: baseTick(), detectEquipmentUpdates(reflection), aiStep(), foodData.tick(), updatePlayerPose()
  - `isSleeping()` — 실제 플레이어가 자면 true 반환 (수면 투표 지원)
  - `isInvulnerableTo()` — 항상 true (무적)
  - `die()` / `awardStat()` / `displayClientMessage()` / `updateOptions()` — no-op
- **테스트 가능성**: In-Game Only. ServerPlayer 상속 + 리플렉션 + 전체 엔티티 시스템 필요
- **테스트 가능한 로직**: 없음 (Minecraft 틱 체인 의존)
- **발견된 이슈**:
  - `DETECT_EQUIPMENT_UPDATES` 리플렉션 실패 시 silent null — detectEquipmentUpdates 호출 스킵만 하고 에이전트 장비가 다른 플레이어에 보이지 않음
  - DEBUG 로깅 `currentTick % 20 == 0`에서 매 1초마다 위치/속도 로그 출력 — 프로덕션에서 로그 스팸

### AgentContext.java
- **역할**: 에이전트별 상태 번들 (player, actionManager, persona, interventionQueue, sessionId)
- **의존성**: [Minecraft 불필요*] ServerPlayer(타입 참조만), ActiveActionManager, InterventionQueue, PersonaConfig. *생성 시 ServerPlayer 인스턴스 필요하나 mock 가능
- **핵심 로직**:
  - 생성자 — name, player, persona 받아 actionManager, interventionQueue, sessionId 초기화
  - `isRuntimeRunning()` — `runtimeProcess != null && runtimeProcess.isAlive()`
  - `resetSession()` — 새 UUID 생성 + hasLaunched=false
- **테스트 가능성**: Unit (mock ServerPlayer). 상태 번들 POJO
- **테스트 가능한 로직**: isRuntimeRunning 상태 전이, resetSession 동작, getter/setter 일관성
- **발견된 이슈**:
  - `runtimeProcess` 필드 비-volatile: RuntimeManager 스레드에서 set, 서버 스레드에서 read — 가시성 보장 없음
  - `sessionId` 비-volatile: 같은 문제
  - `stoppedByUser` 필드 set은 되지만 read하는 곳이 RuntimeManager.launch()의 종료 핸들러뿐 — 필드 존재 의미는 있으나 isStoppedByUser()가 외부에서 사용되지 않음
  - `getFakePlayer()` deprecated 메서드 — 제거 대상

### AgentManager.java
- **역할**: 멀티 에이전트 싱글톤 매니저. 스폰/디스폰, 인벤토리 영속화, 패킷 브로드캐스트
- **의존성**: [Minecraft 필수] MinecraftServer, ServerLevel, ServerPlayer, GameProfile, 패킷 클래스들, NBT I/O, FMLPaths
- **핵심 로직**:
  - `spawn(name, level, pos)` — GameProfile 생성, AgentPlayer 인스턴스화, AgentNetHandler 설정, PERSONA.md 로드, 인벤토리 로드, AgentContext 생성, PlayerInfo+AddPlayer+EntityData 패킷 브로드캐스트, 월드 등록
  - `despawn(name)` — 패킷으로 제거, 인벤토리 저장, 엔티티 discard
  - `despawnAll()` — Set.copyOf로 동시 수정 방지 후 전원 디스폰
  - `sendAgentInfoToPlayer(player)` — 새 접속 플레이어에게 기존 에이전트 정보 전송
  - `saveInventory()` / `loadInventory()` — NBT 직렬화, atomic write (tmp → move)
- **테스트 가능성**: In-Game Only. ServerLevel, 패킷 시스템, NBT I/O 모두 Minecraft 의존
- **테스트 가능한 로직**: ConcurrentHashMap 기반 CRUD (getAgent, isSpawned 등) — 단 spawn/despawn 자체는 불가
- **발견된 이슈**:
  - `spawn()` 내 check-then-act 레이스: `containsKey` 체크 후 `put` 사이에 다른 스레드가 같은 이름으로 spawn 시도 가능 → `putIfAbsent` 사용 필요
  - `server` 필드 null 체크 부재: `spawn()` 내 `new AgentPlayer(server, ...)` — server가 null이면 NPE
  - `loadInventory()` IOException 발생 시 로그만 남기고 진행 — 데이터 불일치 가능하나 의도적 fallback

### AgentNetHandler.java
- **역할**: AgentPlayer의 mock 네트워크 핸들러. EmbeddedChannel로 ServerPlayer.tick() NPE 방지
- **의존성**: [Minecraft 필수] ServerGamePacketListenerImpl, Connection, Netty EmbeddedChannel
- **핵심 로직**:
  - `createMockConnection()` — Connection 생성 후 리플렉션으로 channel 필드에 EmbeddedChannel 설정. 필드명 불일치 시 타입 기반 fallback 탐색
  - `tick()` — no-op (keep-alive 체크 비활성)
  - `send()` — no-op (발신 패킷 흡수)
- **테스트 가능성**: In-Game Only. Connection/ServerGamePacketListenerImpl이 Minecraft 서버 환경 필요
- **테스트 가능한 로직**: 없음
- **발견된 이슈**:
  - 리플렉션 실패 시 `channel` 필드를 설정하지 못하면 로그만 출력 — 이후 패킷 전송 시 NPE 발생 가능
  - 난독화 환경 대비 타입 기반 fallback이 있으나 brittle (Channel 타입 필드가 여러 개이면 잘못된 필드 설정 가능)

### AgentAnimation.java
- **역할**: lookAt(시선 회전) + swingArm(팔 흔들기) 애니메이션 패킷 브로드캐스트
- **의존성**: [Minecraft 필수] Packet, ClientboundTeleportEntityPacket, ClientboundRotateHeadPacket, ClientboundAnimatePacket, AgentManager
- **핵심 로직**:
  - `lookAt(agent, x, y, z)` — yaw/pitch 계산 후 TeleportEntity + RotateHead 패킷 브로드캐스트
  - `swingArm(agent)` — AnimatePacket 브로드캐스트
  - `broadcast(packet)` — 서버의 모든 실제 플레이어에게 패킷 전송
- **테스트 가능성**: In-Game Only. 패킷 시스템 의존
- **테스트 가능한 로직**: yaw/pitch 수학 로직은 추출하면 unit test 가능하나 현재 메서드 내 인라인
- **발견된 이슈**:
  - 스로틀링 없음: 매 틱 호출 가능 → 모든 플레이어에게 매 틱 패킷 브로드캐스트 오버헤드
  - AgentAnimation.broadcast()를 AgentTickHandler에서 20틱마다 TeleportEntity로 호출 + 액션에서 매 틱 호출 → 동일 패킷 중복 전송 가능

### AgentLogger.java
- **역할**: 구조화 JSONL 로거. 세션별 파일 생성, 액션/서브스텝/이벤트 기록
- **의존성**: [Minecraft 불필요*] Gson, FMLPaths. *FMLPaths만 mock하면 독립 실행 가능
- **핵심 로직**:
  - `startSession()` — 타임스탬프 기반 .jsonl 파일 생성, BufferedWriter 초기화
  - `logAction(action, params, result, durationMs)` — 액션 실행 기록
  - `logSubStep(parentAction, stepIndex, detail, ok, error)` — 영역/시퀀스 액션의 하위 단계 기록
  - `logEvent(eventName, data)` — 스폰/디스폰 등 이벤트 기록
  - `endSession()` — 세션 종료 기록, writer 닫기
  - `sanitizeParams(params)` — params에서 action 필드 제거 (중복 방지)
- **테스트 가능성**: Unit (FMLPaths mock 또는 temp dir). 파일 I/O + JSON 직렬화
- **테스트 가능한 로직**: startSession/endSession 라이프사이클, logAction JSON 형식, sanitizeParams 동작
- **발견된 이슈**:
  - `writer` 필드 스레드 안전성: 여러 에이전트가 동시에 logAction 호출 가능 → BufferedWriter 동시 쓰기 → 깨진 JSON 라인 가능
  - 로그 로테이션/정리 없음: 세션마다 파일 무한 누적
  - `sanitizeParams(params)` 내 `params.deepCopy()` — params가 null이면 NPE (호출자인 AgentHttpServer에서는 항상 non-null이나 방어 코드 없음)

### AgentTickHandler.java
- **역할**: Forge 서버 틱 이벤트 핸들러. 모든 에이전트의 액션 틱 + 자동 아이템 픽업
- **의존성**: [Minecraft 필수] TickEvent, ServerLevel, ServerPlayer, ItemEntity, AgentManager, ScheduleManager
- **핵심 로직**:
  - `onServerTick()` — END 페이즈에서: (1) ScheduleManager.tick() 호출, (2) 모든 에이전트 순회 → actionManager.tick → agent.tick → 20틱마다 위치 동기화 → pickupNearbyItems
  - `pickupNearbyItems()` — 2블록 반경 아이템 엔티티 탐색, inventory.add() 후 discard
  - `onPlayerJoin()` — 새 플레이어 접속 시 에이전트 정보 패킷 전송
- **테스트 가능성**: In-Game Only. Forge 이벤트 시스템, ServerLevel, 엔티티 시스템 전부 필요
- **테스트 가능한 로직**: 없음 (모든 로직이 Minecraft API 직접 호출)
- **발견된 이슈**:
  - **CRITICAL**: `pickupNearbyItems()` — `agent.getInventory().add(stack.copy())` 반환값 체크함 (true일 때만 discard) → 이 부분은 정상. 단, add()가 일부만 추가하는 경우(스택 분할) copy()를 사용하므로 원본 아이템은 통째로 discard → 부분 수량 손실 가능
  - 에이전트 간 예외 격리 없음: 한 에이전트의 tick에서 예외 발생 시 나머지 에이전트 틱 스킵
  - `getAllAgents()` 순회 중 ConcurrentHashMap values() — 동시 수정에 weakly consistent이므로 크래시는 안나지만, 순회 중 추가/제거된 에이전트 처리 불일치 가능

### ObservationBuilder.java
- **역할**: 에이전트의 상태 관찰 JSON 빌드 (위치, 인벤토리, 주변 블록/엔티티, 메모리, 지인)
- **의존성**: [Minecraft 필수] ServerPlayer, ServerLevel, BlockState, Entity, ForgeRegistries, MemoryManager, AgentManager
- **핵심 로직**:
  - `build(agent, level, agentName)` — position, inventory, nearby_blocks, nearby_entities, memories, acquaintances 섹션 생성
  - `buildNearbyBlocks()` — 반경 8 블록 내 비-공기 블록 수집, 거리 정렬, 상위 100개 반환. 블록 상태 속성(age, facing 등) 포함
  - `buildNearbyEntities()` — 반경 16 블록 엔티티, 타입/위치/체력 포함
  - `buildMemories()` — title_index (전체 메모리, 거리순) + auto_loaded (근거리 메모리 전체 내용)
  - `buildAcquaintances()` — 페르소나의 지인 정보 + 스폰 여부/거리
- **테스트 가능성**: In-Game Only. ServerLevel, BlockState, Entity 시스템 전부 필요
- **테스트 가능한 로직**: buildMemories 로직은 MemoryManager mock으로 부분 테스트 가능
- **발견된 이슈**:
  - `buildNearbyBlocks()` — O(17^3) = 4913 블록 스캔 → ArrayList 수집 → sort → 매 관찰마다 실행. 성능 부하
  - `buildNearbyEntities()` — 엔티티 수 상한 없음 → 대규모 몹팜 근처에서 JSON 크기 폭발 가능
  - `buildMemories()` — agentName이 null일 때 getAllForTitleIndex에 null 전달 → 필터링 로직에 따라 전체 메모리 반환 (의도적일 수 있으나 문서화 없음)

### PersonaConfig.java
- **역할**: PERSONA.md 파일 파서. Role, Personality, Tools, Acquaintances 섹션 추출
- **의존성**: [Minecraft 불필요] 순수 Java I/O + 문자열 파싱
- **핵심 로직**:
  - `parse(name, personaFile)` — 파일 읽기 → parseContent 호출. 실패 시 defaultPersona 반환
  - `parseContent(name, content)` — 라인별 `## ` 헤더 파싱 → parseAllSections 위임
  - `parseAllSections()` — switch 기반 섹션 분류: role, personality, tools, acquaintances
  - `parseToolsList()` — `- ` / `* ` 리스트 항목 추출
  - `parseAcquaintancesList()` — `- name: description` 형식 파싱
  - `defaultPersona()` — 기본 페르소나 (빈 tools = 모든 도구 허용)
  - `getToolsCsv()` — 도구 목록을 CSV로 반환
- **테스트 가능성**: Unit. 순수 문자열 파싱, 파일 I/O만 mock하면 완전 독립 테스트 가능
- **테스트 가능한 로직**: 섹션 파싱 정확성, 빈 파일 처리, 기본값, tools CSV 생성, acquaintances 파싱
- **발견된 이슈**:
  - `saveParsedSection()` — 빈 메서드 (사용되지 않음). `parseContent()`에서 호출하지만 결국 `parseAllSections()`으로 전체 재파싱 → 죽은 코드 + 중복 파싱
  - 섹션 이름 대소문자: `toLowerCase()`로 변환하므로 `## ROLE`도 인식하나, tools 리스트 항목은 그대로 저장 → `move_to` vs `Move_To` 불일치 가능
  - 도구 이름 whitespace trimming: `substring(2).trim()`으로 처리되나, 중간 공백이나 탭은 보존됨

---

## 3. core/action/ (21 files)

### Action.java (interface)
- **역할**: 동기 액션 인터페이스. `getName()` + `execute(ServerPlayer, JsonObject) → JsonObject`
- **의존성**: [Minecraft 필요] ServerPlayer 타입 참조
- **핵심 로직**: `getName()`, `execute()` 두 메서드 정의
- **테스트 가능성**: N/A (인터페이스)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음

### AsyncAction.java (interface)
- **역할**: 멀티 틱 비동기 액션 인터페이스. start() → CompletableFuture, tick(), cancel()
- **의존성**: [Minecraft 필요] ServerPlayer 타입 참조, CompletableFuture
- **핵심 로직**:
  - `start(agent, params)` → CompletableFuture<JsonObject>
  - `tick(agent)` — 매 틱 호출
  - `cancel()` — 조기 취소
  - `getTimeoutMs()` — 기본 60초
  - `execute()` default — start() 후 future.get() 동기 블로킹
- **테스트 가능성**: N/A (인터페이스)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**:
  - default `execute()` — `future.get()`으로 서버 스레드 블로킹. HTTP bridge가 AsyncAction을 감지해 start() 직접 호출하므로 실제로는 사용되지 않으나, 실수로 직접 호출 시 서버 행 위험
  - tick()과 cancel() 동시 호출 시 CompletableFuture 이중 complete 가능성 — 구현체에서 방어 필요

### ActiveActionManager.java
- **역할**: 에이전트별 단일 활성 비동기 액션 관리자. 새 액션 시작 시 기존 자동 취소
- **의존성**: [Minecraft 필요] ServerPlayer 타입 참조. AsyncAction
- **핵심 로직**:
  - `startAction(action, agent, params)` — 기존 액션 cancel 후 새 액션 start
  - `tick(agent)` — 활성 액션 tick 호출
  - `cancel()` — 현재 액션 cancel + null 초기화
  - `getCurrentAction()` — 현재 활성 액션 반환
- **테스트 가능성**: Unit (mock AsyncAction + mock ServerPlayer). 상태 머신 동작 검증 가능
- **테스트 가능한 로직**: 단일 액션 강제, cancel→start 전환, 비활성 시 tick 무시
- **발견된 이슈**:
  - `getCurrentAction()` TOCTOU: null 체크 후 사용 사이에 다른 스레드가 startAction으로 교체 가능. 실제로는 서버 스레드 단일이므로 문제 없으나 문서화 필요
  - cancel() 후 `currentAction = null` 설정하지만 currentFuture는 null로 안 함 — 메모리 유지

### ActionRegistry.java
- **역할**: 액션 이름 → 인스턴스(sync) 또는 클래스(async) 매핑 레지스트리
- **의존성**: [Minecraft 불필요] 순수 Java 리플렉션 + Map
- **핵심 로직**:
  - `register(Action)` — sync 액션 공유 인스턴스 저장
  - `registerAsync(Class)` — 리플렉션으로 템플릿 생성 → 이름 추출 → 클래스 저장
  - `get(name)` — sync 우선 조회, 없으면 createAsync
  - `createAsync(name)` — 리플렉션 newInstance (매 실행마다 신규)
  - `isAsync(name)` — async 여부 확인
  - `listNames()` — 전체 액션 이름 목록
- **테스트 가능성**: Unit. 순수 Java, mock 불필요
- **테스트 가능한 로직**: 등록/조회, sync vs async 구분, 중복 이름 처리, 미등록 이름 조회
- **발견된 이슈**:
  - 리플렉션 `getDeclaredConstructor().newInstance()` 실패 시 silent — 에러 로그만 남기고 해당 액션 미등록
  - 무인자 생성자 요구: AsyncAction 구현체에 no-arg constructor 없으면 등록 실패
  - 같은 이름 재등록 시 기존 덮어쓰기 (allNames.contains 체크로 리스트 중복은 방지하나 Map은 overwrite)

### MoveToAction.java
- **역할**: A* 경로탐색 + 틱 기반 이동 비동기 액션
- **의존성**: [Minecraft 필수] ServerLevel, ServerPlayer, Pathfinder, PathFollower, AgentAnimation
- **핵심 로직**:
  - `start()` — 목적지 파싱, Pathfinder.findPath 호출, PathFollower.start
  - `tick()` — PathFollower.tick + 완료/스턱 감지 (40틱 동일 위치 = stuck)
- **테스트 가능성**: In-Game Only. ServerLevel 경로탐색 의존
- **테스트 가능한 로직**: 스턱 감지 로직 (틱 카운터 기반)
- **발견된 이슈**:
  - 매 틱 위치 브로드캐스트 (lookAt 호출) → 패킷 오버헤드
  - stuck 감지 40틱 (2초) — 좁은 공간에서 경로 우회 시 정상 이동도 stuck 판정 가능

### MineBlockAction.java
- **역할**: 단일 블록 채굴. 크랙 애니메이션 + 드롭 수집 대기
- **의존성**: [Minecraft 필수] ServerLevel, BlockState, AgentAnimation
- **핵심 로직**:
  - 상태 머신: MINING (크랙 진행) → COLLECTING (드롭 수집 대기)
  - 채굴 시간 계산: `getDestroySpeed()` 기반
- **테스트 가능성**: In-Game Only. 블록 상태/파괴 시스템 의존
- **테스트 가능한 로직**: 상태 전이 로직 (mock으로 부분 가능)
- **발견된 이슈**: 없음 (안정적 구현)

### MineAreaAction.java
- **역할**: 직사각형 영역 채굴 (최대 256블록). 이동+채굴 상태 머신
- **의존성**: [Minecraft 필수] ServerLevel, Pathfinder, PathFollower, AgentAnimation
- **핵심 로직**:
  - 영역 블록 리스트 생성 → 순차 이동+채굴
  - 동적 타임아웃: 블록 수 × 5초 (최대 300초)
  - 채굴 결과 LinkedHashMap으로 아이템별 수량 집계
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 타임아웃 계산, 영역 크기 검증
- **발견된 이슈**:
  - LinkedHashMap 아이템 집계 — 대규모 영역에서 다양한 아이템 종류 시 메모리 증가 (256블록 제한이 있어 실질적 문제 없음)

### UseItemOnAction.java
- **역할**: 블록 면에 오른쪽 클릭 (동기). 호, 씨앗 심기 등
- **의존성**: [Minecraft 필수] ServerLevel, BlockHitResult, InteractionHand, AgentAnimation
- **핵심 로직**:
  - `execute()` — 거리 체크 (4.5블록), face 방향 파싱, `gameMode.useItemOn()` 호출
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: face 문자열 → Direction 파싱
- **발견된 이슈**:
  - face 파싱: 잘못된 문자열 시 기본 Direction.UP 사용 — 명시적 에러 대신 silent fallback
  - `agent.gameMode` null 체크 없음 — AgentPlayer에서 항상 non-null이나 방어 코드 부재

### UseItemOnAreaAction.java
- **역할**: 2D 영역 오른쪽 클릭 (비동기). 서펜타인 이동 패턴
- **의존성**: [Minecraft 필수] ServerLevel, Pathfinder, PathFollower, AgentAnimation
- **핵심 로직**:
  - 서펜타인 패턴 위치 생성 → 순차 이동+사용
  - 동적 타임아웃: 위치 수 × 3초 (최대 300초)
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 서펜타인 패턴 생성 로직
- **발견된 이슈**:
  - **HIGH**: `failureDetails`가 `new JsonArray()`로 초기화되는데, 실패 상세 기록 후 `failureDetails.asList().clear()` 호출 시 no-op BUG — `JsonArray.asList()`는 내부 리스트의 view를 반환하지만 clear()가 원본에 반영되지 않을 수 있음 (Gson 버전에 따라). 실패 이력이 누적됨
  - face 파싱 null 반환 시 즉시 실패 — 정상 처리

### SequenceAction.java
- **역할**: 액션 배열 순차 실행기 (비동기). sync 체인은 같은 틱에 연속 실행
- **의존성**: [Minecraft 필수] ActionRegistry, AgentLogger
- **핵심 로직**:
  - `start()` — steps 파싱, 상태 초기화
  - `tick()` — 현재 sub-action tick 또는 다음 step 진행
  - `processNextStep()` — sync면 즉시 실행 + 재귀, async면 start + whenComplete 콜백
  - `cancel()` — sub-action cancel + 부분 결과 반환
- **테스트 가능성**: Integration (mock ActionRegistry + mock Action 구현). 상태 머신 + 재귀 로직
- **테스트 가능한 로직**: sync 체인 재귀, async→sync 전환, 실패 시 중단, 부분 결과 반환
- **발견된 이슈**:
  - sync 체인 재귀 — 모든 step이 sync이면 무제한 재귀. 실제로 sync 액션 수십 개 체인은 드물지만 stack overflow 이론적 가능
  - whenComplete 콜백에서 currentStepIndex++ — 서버 스레드 외에서 실행될 수 있음 (CompletableFuture 완료 스레드)

### CraftAction.java
- **역할**: 실제 크래프팅. 레시피 매칭, 재료 소모, 결과물 생성. 배치 지원
- **의존성**: [Minecraft 필수] RecipeManager, CraftingRecipe, ServerLevel, ForgeRegistries
- **핵심 로직**:
  - `execute()` — 레시피 조회 → 크래프팅 테이블 필요 여부 확인 → 재료 매칭 → count회 반복 (재료 소모 + 결과물 생성)
  - `matchIngredients()` — 인벤토리 슬롯 → 재료 매칭 (중복 사용 방지)
  - `hasCraftingTableNearby()` — 4.5블록 이내 작업대 탐색
- **테스트 가능성**: In-Game Only. RecipeManager, 인벤토리 시스템 의존
- **테스트 가능한 로직**: matchIngredients 매칭 알고리즘 (mock Ingredient + mock Inventory로 가능)
- **발견된 이슈**:
  - **CRITICAL**: `agent.getInventory().add(resultStack)` 반환값 미체크 — 인벤토리가 가득 차면 결과물이 add 실패하지만 재료는 이미 소모된 상태 → 아이템 소실

### EquipAction.java
- **역할**: 아이템을 지정 슬롯에 장착 (동기)
- **의존성**: [Minecraft 필수] ServerPlayer, 인벤토리 시스템
- **핵심 로직**: 인벤토리에서 아이템 찾기 → 지정 슬롯으로 이동
- **테스트 가능성**: In-Game Only (인벤토리 시스템 의존)
- **테스트 가능한 로직**: 슬롯 이름 → 슬롯 번호 매핑
- **발견된 이슈**: 없음

### AttackAction.java
- **역할**: 엔티티 공격 (동기). 6블록 거리 제한
- **의존성**: [Minecraft 필수] ServerLevel, Entity, AgentAnimation
- **핵심 로직**: entity ID로 엔티티 찾기 → 거리 체크 → agent.attack() 호출 → swingArm 애니메이션
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### InteractAction.java
- **역할**: 엔티티 오른쪽 클릭 (동기). 거래, 번식 등
- **의존성**: [Minecraft 필수] ServerLevel, Entity, InteractionHand
- **핵심 로직**: entity ID로 엔티티 찾기 → 거리 체크 → `entity.interact()` 호출
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**:
  - swingArm 애니메이션 호출 없음 — AttackAction과 불일치

### PlaceBlockAction.java
- **역할**: 블록 배치 (동기). 인벤토리에서 아이템 소모, `level.setBlock()` 직접 호출
- **의존성**: [Minecraft 필수] ServerLevel, Block, ForgeRegistries
- **핵심 로직**: 블록 ID 해석 → 거리 체크 (6블록) → 인벤토리 매칭 → setBlock + 아이템 감소
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**:
  - **HIGH**: `block.asItem()` 반환값 null 가능 — 일부 블록(bedrock 등)은 대응 아이템 없음. `ForgeRegistries.ITEMS.getKey(null)` → NPE

### DropItemAction.java
- **역할**: 아이템 드롭 (동기). pickUpDelay=40 (2초)
- **의존성**: [Minecraft 필수] ServerPlayer
- **핵심 로직**: 인벤토리에서 아이템 찾기 → agent.drop() 호출
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### OpenContainerAction.java
- **역할**: 컨테이너 열기 (동기). state.use()로 오른쪽 클릭 시뮬레이션
- **의존성**: [Minecraft 필수] ServerLevel, BlockState, BlockHitResult
- **핵심 로직**: `state.use(level, agent, MAIN_HAND, hit)` → containerMenu 열렸는지 확인 → 슬롯 내용 반환
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**:
  - **HIGH**: 거리 체크 없음 — 어떤 거리에서든 컨테이너 열기 가능 (바닐라에서는 약 5블록 제한)
  - `Direction.UP` 하드코딩 — 모든 컨테이너에 위쪽 면 클릭. 대부분 문제 없으나 방향 민감 블록에서 오작동 가능

### ClickSlotAction.java
- **역할**: 컨테이너 슬롯 클릭 (동기)
- **의존성**: [Minecraft 필수] ClickType, ServerPlayer
- **핵심 로직**: containerMenu.clicked() 호출. clickType 문자열 → ClickType enum 매핑
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: clickType 문자열 파싱
- **발견된 이슈**:
  - 알 수 없는 clickType → 기본 PICKUP — silent fallback, 에러 반환 없음
  - slot=-999 (드롭) 미지원

### ContainerTransferAction.java
- **역할**: 여러 아이템 컨테이너 간 이동 (동기). from→to 또는 quick_move
- **의존성**: [Minecraft 필수] ClickType, ServerPlayer, ForgeRegistries
- **핵심 로직**:
  - moves 배열 순회 → 각 이동별 QUICK_MOVE 또는 PICKUP→PICKUP
  - 스왑 발생 시 원래 슬롯에 되돌리기 → 실패 시 drop으로 stuck 방지
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**:
  - 스왑 핸들링: 목적지 점유 시 원래 슬롯에 되돌리고 "slot_occupied" 에러 반환 — drop fallback으로 stuck 방지는 됨
  - `getCarried()` 잔류 아이템 drop 시 위치가 에이전트 발 아래 → 자동 픽업으로 즉시 재수거 가능

### CloseContainerAction.java
- **역할**: 열린 컨테이너 닫기 (동기)
- **의존성**: [Minecraft 필수] ServerPlayer
- **핵심 로직**: `agent.closeContainer()` 단일 호출
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음 (trivial wrapper)

### SmeltAction.java
- **역할**: 제련 레시피 조회 (동기). 정보만 반환, 실제 제련 수행 안 함
- **의존성**: [Minecraft 필수] RecipeManager, SmeltingRecipe
- **핵심 로직**: 아이템 이름으로 제련 레시피 검색 → 입력/출력/시간 정보 반환
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

---

## 4. core/memory/ (14 files)

### MemoryEntry.java
- **역할**: 메모리 항목 기본 데이터 모델 (id, title, description, content, category, visibleTo, timestamps)
- **의존성**: [Minecraft 불필요] 순수 Java POJO
- **핵심 로직**: getter/setter, `markUpdated()`, `isVisibleTo(agentName)` 가시성 판정
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: visibleTo 로직 (global/agent-specific/multi-agent), markUpdated 타임스탬프
- **발견된 이슈**: 없음

### MemoryManager.java
- **역할**: 메모리 CRUD + 파일 영속화 + scoped 쿼리 + @reference 해석
- **의존성**: [Minecraft 불필요*] Gson, FMLPaths, CopyOnWriteArrayList. *FMLPaths만 mock 필요
- **핵심 로직**:
  - `load()` — v1(bare array) / v2(versioned object) 형식 자동 감지, 에이전트별 구 파일 마이그레이션
  - `save()` — atomic write (tmp → move)
  - `create/update/delete()` — CopyOnWriteArrayList 기반 CRUD
  - `search(keyword, category, scope)` — 키워드 + 카테고리 + 스코프 필터
  - `getAllForTitleIndex()` — 거리순 정렬 인덱스
  - `getAutoLoadContent()` — 근거리(32블록) 항목 + 카테고리별 규칙 기반 자동 로드
  - `resolveReferences()` — @memory:mXXX 패턴 → 참조 메모리 로드 (depth=3 제한)
- **테스트 가능성**: Unit/Integration (temp dir). 모든 핵심 로직 테스트 가능
- **테스트 가능한 로직**: CRUD, v1→v2 마이그레이션, @reference 해석 + 순환 참조 방지, 스코프 필터링, 거리 기반 자동 로드
- **발견된 이슈**:
  - `getAutoLoadContent()` — Locatable 항목의 `getLocation().distanceTo()` — getLocation()이 null 반환 시 NPE
  - `nextIdCounter` 동기화: CopyOnWriteArrayList는 쓰기 동기화하지만 nextId 증가는 별도 보호 없음 → 동시 create 시 ID 충돌 가능성 (create가 synchronized면 해결되나 확인 필요)
  - AUTO_LOAD_RADIUS=32 하드코딩 — 설정 불가

### MemoryLocation.java (interface)
- **역할**: 위치 인터페이스. `getType()`, `distanceTo(x,y,z)`, `isWithinRange(x,y,z,range)`
- **의존성**: [Minecraft 불필요] 순수 인터페이스
- **핵심 로직**: N/A
- **테스트 가능성**: N/A
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음

### PointLocation.java
- **역할**: 3D 점 위치. 유클리드 거리 계산
- **의존성**: [Minecraft 불필요] 순수 수학
- **핵심 로직**: `distanceTo(x,y,z)` — 3D 유클리드 거리, `isWithinRange()` — 거리 ≤ range
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: 거리 계산 정확성, 경계값
- **발견된 이슈**: 없음

### AreaLocation.java
- **역할**: 2D 영역 위치 (x1,z1 → x2,z2, 고정 y). 최근접점 거리 계산
- **의존성**: [Minecraft 불필요] 순수 수학
- **핵심 로직**: `distanceTo(x,y,z)` — 박스 최근접점까지의 3D 거리
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: 영역 내/외 거리 계산, 경계값
- **발견된 이슈**: 없음

### Locatable.java (interface)
- **역할**: `getLocation()` 마커 인터페이스 — 위치를 가진 메모리 항목 표시
- **의존성**: [Minecraft 불필요] 순수 인터페이스
- **핵심 로직**: `getLocation()` → MemoryLocation (nullable)
- **테스트 가능성**: N/A
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음

### StorageMemory.java
- **역할**: 저장소 위치 (PointLocation 필수). MemoryEntry + Locatable 구현
- **의존성**: [Minecraft 불필요] MemoryEntry, Locatable, PointLocation
- **핵심 로직**: location getter/setter
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: location 설정/조회
- **발견된 이슈**: 없음

### FacilityMemory.java
- **역할**: 시설 (MemoryLocation — point 또는 area). MemoryEntry + Locatable 구현
- **의존성**: [Minecraft 불필요] MemoryEntry, Locatable, MemoryLocation
- **핵심 로직**: location getter/setter
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: location 설정/조회
- **발견된 이슈**: 없음

### AreaMemory.java
- **역할**: 명명 영역 (AreaLocation 필수). MemoryEntry + Locatable 구현
- **의존성**: [Minecraft 불필요] MemoryEntry, Locatable, AreaLocation
- **핵심 로직**: location getter/setter (AreaLocation 타입)
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: 없음 (단순 데이터 클래스)
- **발견된 이슈**: 없음

### EventMemory.java
- **역할**: 이벤트 기록 (위치 선택적). MemoryEntry + Locatable 구현
- **의존성**: [Minecraft 불필요] MemoryEntry, Locatable
- **핵심 로직**: location getter/setter (nullable)
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### SkillMemory.java
- **역할**: 학습된 스킬. 위치 없음. MemoryEntry 상속
- **의존성**: [Minecraft 불필요] MemoryEntry
- **핵심 로직**: 추가 필드 없음
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### ScheduleMemory.java
- **역할**: 스케줄 항목. ScheduleConfig를 first-class 필드로 포함. MemoryEntry 상속
- **의존성**: [Minecraft 불필요] MemoryEntry, ScheduleConfig
- **핵심 로직**: config getter/setter
- **테스트 가능성**: Unit
- **테스트 가능한 로직**: config 설정/조회
- **발견된 이슈**:
  - memory 패키지 ↔ schedule 패키지 순환 의존 — ScheduleMemory가 ScheduleConfig 참조, ScheduleManager가 ScheduleMemory 참조

### MemoryEntryTypeAdapter.java
- **역할**: Gson 다형성 역직렬화. category 필드로 MemoryEntry 서브클래스 디스패치
- **의존성**: [Minecraft 불필요] Gson
- **핵심 로직**:
  - `deserialize()` — category 읽기 → 서브클래스 결정 (storage→StorageMemory, facility→FacilityMemory 등) → 내부 Gson으로 역직렬화
  - `serialize()` — 내부 Gson으로 직렬화
- **테스트 가능성**: Unit. Gson만 필요
- **테스트 가능한 로직**: 카테고리별 올바른 서브클래스 생성, 알 수 없는 카테고리 처리, 직렬화 왕복
- **발견된 이슈**:
  - 내부 Gson (`new Gson()`)에 MemoryLocationTypeAdapter/ScheduleConfigTypeAdapter 미등록 — 재귀 방지를 위한 의도적 설계이나 location/config 필드가 기본 Gson으로 역직렬화됨. MemoryLocationTypeAdapter 없이도 동작하는지 확인 필요
  - 기본 필드(id, title 등) 수동 설정 후 내부 Gson 결과를 반환 — base 필드가 이중으로 설정될 수 있는 구조

### MemoryLocationTypeAdapter.java
- **역할**: Gson point/area 위치 역직렬화 + v1 하위호환
- **의존성**: [Minecraft 불필요] Gson
- **핵심 로직**:
  - `deserialize()` — type 필드로 point/area 구분, 없으면 필드 존재 여부로 추론 (하위호환)
  - `serialize()` — point/area에 따라 적절한 필드 출력
- **테스트 가능성**: Unit. Gson만 필요
- **테스트 가능한 로직**: point/area 역직렬화, v1 형식 하위호환, 필드 누락 처리
- **발견된 이슈**:
  - 누락 필드 기본값 0.0 — x, y, z 중 하나라도 없으면 silent 0.0. 위치가 (0,0,0)이 되어 오작동 가능

---

## 5. core/schedule/ (6 files)

### ScheduleConfig.java
- **역할**: 스케줄 트리거 설정 POJO (TIME_OF_DAY, INTERVAL, OBSERVER)
- **의존성**: [Minecraft 불필요] Gson 어노테이션, ObserverDef
- **핵심 로직**:
  - `toJson()` — type별 필드 선택적 직렬화
  - `fromJson()` — type별 필드 선택적 역직렬화
  - getter/setter 전체
- **테스트 가능성**: Unit. 순수 POJO + JSON
- **테스트 가능한 로직**: toJson/fromJson 왕복, type별 필드 존재 여부, 기본값
- **발견된 이슈**:
  - **CRITICAL**: `fromJson()` — `Type.valueOf(obj.get("type").getAsString())` — "type" 키 없거나 잘못된 값이면 NPE 또는 IllegalArgumentException (catch 없음)
  - **CRITICAL**: `intervalTicks=0` 설정 시 `ScheduleManager.checkInterval()`에서 `elapsed % 0` → ArithmeticException (0으로 나누기)
  - `timeOfDay` 범위 검증 없음 — 0-23999 외 값 허용
  - `intervalTicks` 최소값 검증 없음 — 0 또는 음수 허용

### ObserverDef.java
- **역할**: 개별 관찰자 정의 POJO (위치 + 이벤트 타입 + 조건)
- **의존성**: [Minecraft 필요*] BlockPos. *BlockPos만이 유일한 Minecraft 의존. 좌표 3개로 대체 가능
- **핵심 로직**:
  - `toJson()` / `fromJson()` — JSON 직렬화/역직렬화
  - `getBlockPos()` — `new BlockPos(x, y, z)` (매 호출마다 새 인스턴스)
- **테스트 가능성**: Unit (BlockPos는 간단한 값 객체이므로 테스트 환경에서 사용 가능)
- **테스트 가능한 로직**: toJson/fromJson 왕복, 필드 누락 처리
- **발견된 이슈**:
  - `fromJson()` — `obj.get("x").getAsInt()` — x, y, z 키 없으면 NPE. has() 체크 없음
  - `getBlockPos()` — 매 호출 new BlockPos 할당. 핫 경로(processEvent)에서 반복 호출 시 GC 부하

### ScheduleManager.java
- **역할**: 스케줄 CRUD + 틱 기반 트리거 평가 + 트리거 라우팅
- **의존성**: [Minecraft 간접 필요] MemoryManager, AgentManager, RuntimeManager, ObserverManager
- **핵심 로직**:
  - `init()` — MemoryManager에서 schedule 카테고리 로드, lastTriggered 초기화, OBSERVER 등록
  - `create/update/delete()` — synchronized CRUD + ObserverManager 연동
  - `tick(dayTime, dayCount, serverTick)` — scheduleCache 순회하며 트리거 체크
  - `checkTimeOfDay()` — 게임 시각 일치 + 반복일 체크
  - `checkInterval()` — 서버 틱 간격 modulo 체크
  - `trigger()` — managerContext → intervention queue, 또는 직접 agent intervention/launch
- **테스트 가능성**: Integration (mock MemoryManager). 틱 평가 로직은 독립 테스트 가능
- **테스트 가능한 로직**: TIME_OF_DAY 트리거 로직, INTERVAL modulo 로직, 반복/1회 판정, 트리거 라우팅 분기
- **발견된 이슈**:
  - **CRITICAL**: `tick()` — `scheduleCache.values()` 순회 중 `trigger()` 내부에서 `MemoryManager.save()` 호출 → ConcurrentHashMap.values()는 weakly consistent이므로 크래시는 아니나 동시 수정 이슈
  - `init()` — `scheduleCache.clear()` → 기존 상태 전부 리셋. 서버 재시작 시 lastTriggered 정보 유실
  - `trigger()` — 에러 핸들링 없음: agentCtx가 null이면 로그만 출력, RuntimeManager.launch() 실패 시 무시

### ObserverManager.java
- **역할**: Forge 이벤트 기반 OBSERVER 트리거 관리. 이벤트→위치→스케줄 매핑
- **의존성**: [Minecraft 필수] Forge 이벤트 (BlockEvent, LivingDeathEvent 등), ServerLevel, BlockState
- **핵심 로직**:
  - `registerSchedule(sm)` — 관찰자별 registry 등록 (eventType → pos → registrations)
  - `processEvent(eventType, pos, state)` — 이벤트 매칭 → 조건 체크 → threshold 카운팅 → 트리거
  - `processEntityEvent()` — 엔티티 이벤트용 (3블록 반경 매칭)
  - `matchesCondition()` — "age=7" 등 블록 상태 속성 매칭
  - Forge @SubscribeEvent 핸들러 7개: cropGrow, bonemeal, blockBreak, blockPlace, babySpawn, entityDeath, explosion
- **테스트 가능성**: In-Game Only. Forge 이벤트 시스템 전면 의존
- **테스트 가능한 로직**: matchesCondition 파싱 로직, threshold 카운팅
- **발견된 이슈**:
  - 메모리 누수: `triggeredMap` — `triggered.clear()`가 threshold 도달 시에만 호출. threshold 미도달 상태에서 스케줄 삭제 안 하면 triggeredMap 엔트리 누적. `unregisterSchedule()`에서 remove하므로 삭제 시 정리됨. 다만 threshold 미도달 상태에서 장기 실행 시 triggered Set 크기 증가
  - `processEvent` 스레드 안전성: Forge 이벤트는 서버 스레드에서 발생하나, ConcurrentHashMap 내부의 ArrayList는 비동기화 → 등록/해제와 동시 접근 시 ConcurrentModificationException 가능
  - 조건 파싱: `=` 기반 단순 파싱만 지원. `age>=7`, `type=zombie|skeleton` 등 복합 조건 미지원

### ManagerContext.java
- **역할**: Agent Manager POJO 상태. InterventionQueue + 런타임 프로세스 + 세션 ID
- **의존성**: [Minecraft 불필요] InterventionQueue, UUID
- **핵심 로직**:
  - `isRuntimeRunning()` — `runtimeProcess != null && runtimeProcess.isAlive()`
  - `resetSession()` — 새 UUID + hasLaunched=false
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: isRuntimeRunning 상태 전이, resetSession
- **발견된 이슈**:
  - `isRuntimeRunning()` TOCTOU: null 체크와 isAlive() 사이에 프로세스 종료 가능 (실제로는 단일 스레드 접근이므로 문제 적음)
  - `runtimeProcess`, `hasLaunched`, `sessionId` 모두 비-volatile — 멀티스레드 가시성 문제 (ManagerRuntimeManager의 daemon thread에서 set, 서버 스레드에서 read)

### ScheduleConfigTypeAdapter.java
- **역할**: Gson TypeAdapter. ScheduleConfig.toJson()/fromJson() 위임
- **의존성**: [Minecraft 불필요] Gson, ScheduleConfig
- **핵심 로직**:
  - `serialize()` → `src.toJson()`
  - `deserialize()` → `ScheduleConfig.fromJson(obj)` + 디버그 로깅
- **테스트 가능성**: Unit. Gson + ScheduleConfig만 필요
- **테스트 가능한 로직**: serialize/deserialize 왕복
- **발견된 이슈**:
  - `LOGGER.info()` 로그 — 매 역직렬화마다 INFO 레벨 출력 (observer keys, event 등). 디버깅용으로 남아있는 코드 → 프로덕션에서 로그 스팸
  - observers 배열 첫 번째 요소 접근 — 빈 배열이면 NPE는 isEmpty() 체크로 방지, get(0) 전에 체크함

---

## 6. core/pathfinding/ (2 files)

### Pathfinder.java
- **역할**: A* 경로탐색 알고리즘. ServerLevel에서 블록 통행 가능성 판단
- **의존성**: [Minecraft 필요*] ServerLevel(블록 상태 조회), BlockPos. *walkability 함수를 추출하면 mock 가능
- **핵심 로직**:
  - `findPath(level, from, to, maxDistance)` — A* 탐색, Manhattan 휴리스틱
  - `getNeighbors(level, pos)` — 6방향 + 대각선 상하 이동 후보
  - `isWalkable(level, pos)` — 발 위치 비-고체 + 머리 위치 비-고체 + 바닥 고체
- **테스트 가능성**: Integration (walkability 함수 mock). A* 알고리즘 자체는 독립 테스트 가능
- **테스트 가능한 로직**: A* 정확성, 경로 길이 최적성, 장애물 우회
- **발견된 이슈**:
  - `MAX_SEARCH_NODES = 1000` — 장거리 경로탐색에 불충분 (약 32블록 직선 거리 한계)
  - 부분 경로(partial path) 미지원: 경로 못 찾으면 빈 리스트 반환 → 에이전트가 전혀 이동 안 함
  - Manhattan 휴리스틱: Y축 비용이 수평과 동일 → 높이 차 큰 경로에서 비최적

### PathFollower.java
- **역할**: 틱 기반 경로 추종. deltaMovement 설정 → aiStep/travel이 물리 적용
- **의존성**: [Minecraft 필요] ServerPlayer, BlockPos, Vec3
- **핵심 로직**:
  - `start(path)` — 경로 시작
  - `tick(agent)` — 현재 웨이포인트까지 이동 벡터 계산, 도착 시 다음 웨이포인트 진행
  - 점프: `dy > 0.5 && onGround()` → JUMP_VELOCITY
- **테스트 가능성**: Unit (상태 머신). 이동 벡터 계산과 웨이포인트 진행 로직
- **테스트 가능한 로직**: 웨이포인트 진행, 경로 완료 판정, cancel 동작
- **발견된 이슈**:
  - **HIGH**: `WAYPOINT_THRESHOLD = 0.3` — 에이전트 이동 속도(0.215 블록/틱)에 비해 작음. 물리 적용 후 정확히 0.3 이내에 도달 못하면 영원히 진동 가능
  - 점프 로직: `dy > 0.5` — 1블록 계단 올라가기에는 정상이나, step-up(0.6 이하 높이)에서 불필요한 점프 발생
  - 타임아웃 없음: 경로 추종이 무한히 계속될 수 있음 (MoveToAction의 stuck 감지에 의존)

---

## 7. network/ (1 file)

### AgentHttpServer.java
- **역할**: HTTP 브리지 서버. 에이전트별 라우팅, MCP 도구 ↔ Minecraft 연결
- **의존성**: [Minecraft 필수] com.sun.net.httpserver, AgentManager, ActionRegistry, MemoryManager, ScheduleManager, ObserverManager
- **핵심 로직**:
  - `start()` — localhost:0 바인딩, 포트 파일 저장, 라우트 등록
  - `/agent/{name}/observation` — ObservationBuilder 호출
  - `/agent/{name}/action` — Action 실행 (sync: 즉시, async: CompletableFuture + timeout)
  - `/agent/{name}/intervention` — InterventionQueue poll
  - `/agent/{name}/persona` — PersonaConfig 반환
  - `/agents` / `/agents/list` / `/agents/delete` — 에이전트 CRUD
  - `/actions` — 액션 목록
  - `/memory/*` — 메모리 CRUD (create, get, update, delete, search, reload)
  - `/schedule/*` — 스케줄 CRUD (create, update, delete, list, get)
  - `/observer/*` — 관찰자 CRUD (add, remove, states, supported_events)
  - `/manager/intervention` — 매니저 intervention poll
  - `/log` — 로그 전송
- **테스트 가능성**: In-Game Only. Minecraft API 호출을 서버 스레드에서 실행해야 하므로 독립 테스트 불가
- **테스트 가능한 로직**: 라우트 파싱 로직은 추출하면 테스트 가능하나 현재 인라인
- **발견된 이슈**:
  - 인증/인가 없음: localhost 바인딩이므로 외부 접근은 차단되나, 같은 머신의 다른 프로세스가 접근 가능
  - 서버 스레드 위임: Minecraft API 호출을 `server.execute()` 또는 `CompletableFuture.supplyAsync(... server.executor)`로 위임 — 복잡하지만 필요한 구조
  - 파일 크기: 1244줄. 단일 파일에 모든 라우트 핸들러 → 유지보수 부담

---

## 8. runtime/ (2 files)

### RuntimeManager.java
- **역할**: 에이전트별 Node.js 런타임 프로세스 관리 (launch/stop/stopAll)
- **의존성**: [Minecraft 간접] AgentContext, AgentManager, AgentHttpServer, CommandSourceStack, FMLPaths
- **핵심 로직**:
  - `launch(agentName, message, source)` — ProcessBuilder로 node dist/index.js 실행. 환경변수: BRIDGE_PORT, MESSAGE, NAME, SESSION_ID, IS_RESUME, PERSONA_CONTENT, TOOLS
  - stdout 읽기 → `relayToChat()` — JSON 파싱 후 타입별 채팅 메시지 전송 (thought=회색, tool_call=청록, result=녹색, error=빨강)
  - `stop(agentName)` — destroyForcibly
  - `resolveNodeCommand()` — PATH → 일반 위치 → fallback 순서로 node.exe 탐색
- **테스트 가능성**: Integration (mock Process). resolveNodeCommand는 unit 테스트 가능
- **테스트 가능한 로직**: resolveNodeCommand 경로 탐색, relayToChat JSON 파싱
- **발견된 이슈**:
  - 좀비 프로세스 위험: `finally { ctx.setRuntimeProcess(null) }` — 프로세스 종료 전에 참조를 null로 설정. destroyForcibly 전에 스레드가 종료되면 프로세스가 남을 수 있음
  - 프로세스 핸들 스레드 안전성: daemon 스레드에서 setRuntimeProcess, 서버 스레드에서 getRuntimeProcess — volatile 아님
  - `relayToChat()` catch (Exception ignored) — JSON 파싱 실패한 stdout 라인 무시 (Node.js의 비-JSON 출력)

### ManagerRuntimeManager.java
- **역할**: Agent Manager Node.js 런타임 프로세스 관리
- **의존성**: [Minecraft 간접] ManagerContext, ScheduleManager, AgentHttpServer, CommandSourceStack, FMLPaths
- **핵심 로직**:
  - `launchOrMessage(message, source)` — 실행 중이면 intervention queue에 추가, 아니면 신규 프로세스 시작
  - stdout 읽기 → relayToChat (RuntimeManager와 동일 패턴, prefix만 다름)
  - `stop()` — destroyForcibly
- **테스트 가능성**: Integration (mock Process)
- **테스트 가능한 로직**: launchOrMessage 분기 (실행 중 vs 신규)
- **발견된 이슈**:
  - RuntimeManager와 98% 코드 중복 — relayToChat, sendChatLine, 프로세스 관리 로직 모두 복사-붙여넣기
  - 같은 좀비 프로세스/스레드 안전성 문제

---

## 9. command/ (2 files)

### AgentCommand.java
- **역할**: `/agent @<name> <subcommand|message>` 커맨드. 스폰/디스폰/메시지/상태/정지/일시정지/재개
- **의존성**: [Minecraft 필수] Brigadier, CommandSourceStack, AgentManager, RuntimeManager
- **핵심 로직**:
  - Brigadier 커맨드 등록: `/agent` + greedy string 인자
  - `@name` 파싱 → 서브커맨드(spawn, despawn, status, stop, pause, resume) 또는 메시지 판별
  - Tab completion: 스폰된 에이전트 + 디스크 에이전트 제안
  - `list` — 전체 에이전트 목록 (전역, @없이)
- **테스트 가능성**: In-Game Only. Brigadier + Minecraft 커맨드 시스템
- **테스트 가능한 로직**: @name + subcommand 파싱 로직은 추출하면 unit 테스트 가능
- **발견된 이슈**: 없음 (정상적인 커맨드 구현)

### ManagerCommand.java
- **역할**: `/am <message>` — Agent Manager에 메시지 전송
- **의존성**: [Minecraft 필수] Brigadier, CommandSourceStack, ManagerRuntimeManager
- **핵심 로직**: greedy string 인자 → `ManagerRuntimeManager.launchOrMessage()` 호출
- **테스트 가능성**: In-Game Only. Brigadier 의존
- **테스트 가능한 로직**: 없음 (trivial wrapper)
- **발견된 이슈**: 없음

---

## 10. monitor/ (4 files)

### InterventionQueue.java
- **역할**: ConcurrentLinkedQueue 래퍼. 에이전트별 개입 메시지 큐
- **의존성**: [Minecraft 불필요] 순수 Java concurrent
- **핵심 로직**: `add()`, `poll()`, `hasMessages()`, `clear()`
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: FIFO 순서, 빈 큐 poll, 동시성 안전
- **발견된 이슈**: 없음 (trivial 래퍼)

### ChatMonitor.java
- **역할**: 에이전트 활동을 채팅으로 중계. 10틱마다 MonitorLogBuffer 폴링
- **의존성**: [Minecraft 필수] Forge TickEvent, ServerPlayer, Component
- **핵심 로직**:
  - `onServerTick()` — 10틱마다 새 로그 엔트리 조회 → 포맷팅 → 전 플레이어에게 전송
  - `sendFormattedEntry()` — 타입별 색상/접두사: thought=회색, action=흰색, observation=청록, error=빨강 등
  - `wrapText()` — 60자 줄바꿈
- **테스트 가능성**: In-Game Only. Forge 이벤트 + Minecraft 채팅
- **테스트 가능한 로직**: wrapText 줄바꿈 로직 (추출하면 unit test 가능)
- **발견된 이슈**: 없음

### MonitorLogBuffer.java
- **역할**: 에이전트 로그 버퍼 (최대 500개). 새 엔트리 조회 지원
- **의존성**: [Minecraft 불필요] 순수 Java LinkedList
- **핵심 로직**:
  - `add()` — synchronized. 엔트리 추가 + MAX_SIZE 초과 시 오래된 것 제거
  - `getNew()` — synchronized. readIndex 이후 새 엔트리만 반환
  - `getAll()` — 전체 복사본 반환
- **테스트 가능성**: Unit. 완전 독립
- **테스트 가능한 로직**: 버퍼 오버플로, 새 엔트리 추적, 동기화
- **발견된 이슈**: 없음 (단순하고 안전한 구현)

### TerminalIntegration.java
- **역할**: terminal-mod 연동 브리지. terminal-mod 로드 여부 감지 + 로그 전달
- **의존성**: [Minecraft 필수] ModList (Forge), MonitorLogBuffer
- **핵심 로직**:
  - `initialize()` — ModList에서 "terminal" 모드 로드 여부 확인
  - `sendLog()` — MonitorLogBuffer에 항상 전달, terminal-mod 있으면 추가 전달 (미구현)
- **테스트 가능성**: In-Game Only. ModList 의존
- **테스트 가능한 로직**: 없음 (stub)
- **발견된 이슈**: 없음 (Phase 4+ 기능 — 현재 stub)

---

## 11. client/ (7 files)

### ClientSetup.java
- **역할**: 클라이언트 키바인딩 등록 (M=메모리, G=에이전트)
- **의존성**: [Minecraft 필수] Forge 클라이언트, KeyMapping
- **핵심 로직**: 키 매핑 등록 + 틱마다 입력 체크 → 해당 스크린 열기
- **테스트 가능성**: In-Game Only. 클라이언트 환경 필수
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### BridgeClient.java
- **역할**: 클라이언트 → HTTP 서버 비동기 통신 (GUI ↔ server)
- **의존성**: [Minecraft 간접] HttpURLConnection, Gson
- **핵심 로직**: 비동기 HTTP GET/POST, JSON 파싱, 콜백 기반 응답 처리
- **테스트 가능성**: Integration (mock HTTP). HTTP 통신 로직
- **테스트 가능한 로직**: JSON 파싱, 에러 핸들링
- **발견된 이슈**: 없음

### AgentManagementScreen.java
- **역할**: 에이전트 관리 GUI (G키). 에이전트 리스트, 스폰/디스폰, 페르소나 편집
- **의존성**: [Minecraft 필수] Screen, Widget 시스템, BridgeClient
- **핵심 로직**: 에이전트 CRUD UI, 도구 체크박스, 실시간 상태 표시
- **테스트 가능성**: In-Game Only. Minecraft 렌더링 파이프라인
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### MemoryListScreen.java
- **역할**: 메모리 브라우저 GUI (M키). 스코프/카테고리 탭, 검색
- **의존성**: [Minecraft 필수] Screen, Widget 시스템, BridgeClient
- **핵심 로직**: 탭 필터링, 검색, 생성/편집/삭제 버튼
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### MemoryEditScreen.java
- **역할**: 메모리 편집 GUI. 스코프 선택기, 카테고리별 필드
- **의존성**: [Minecraft 필수] Screen, Widget 시스템, BridgeClient
- **핵심 로직**: 폼 입력 → JSON → HTTP POST
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### AreaMarkHandler.java
- **역할**: 월드에서 블록 영역 선택 (2점 클릭)
- **의존성**: [Minecraft 필수] 클라이언트 레벨, 블록 상호작용
- **핵심 로직**: 첫 번째 클릭 → 시작점 저장, 두 번째 클릭 → 영역 확정
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### GuiLayout.java
- **역할**: GUI 레이아웃 헬퍼. 위치/크기 계산 유틸리티
- **의존성**: [Minecraft 불필요*] 순수 계산. *Minecraft Screen 크기 값만 참조
- **핵심 로직**: 마진, 패딩, 정렬 계산
- **테스트 가능성**: Unit (입력값만 제공). 순수 수학
- **테스트 가능한 로직**: 레이아웃 계산 정확성
- **발견된 이슈**: 없음

### DropdownWidget.java
- **역할**: 드롭다운 위젯 컴포넌트
- **의존성**: [Minecraft 필수] Widget 시스템, 렌더링
- **핵심 로직**: 옵션 리스트, 선택 상태 관리, 드롭다운 렌더링
- **테스트 가능성**: In-Game Only
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

---

## 12. compat/ (4 files)

### ModCompat.java (interface)
- **역할**: 모드 호환 인터페이스. getModId(), getActions(), extendObservation()
- **의존성**: [Minecraft 필요] ServerPlayer 타입 참조, Action
- **핵심 로직**: 3 메서드 정의
- **테스트 가능성**: N/A (인터페이스)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음

### CompatRegistry.java
- **역할**: 모드 호환 레지스트리. 로드된 모드 감지 → 액션 자동 등록
- **의존성**: [Minecraft 필수] ModList, ActionRegistry
- **핵심 로직**:
  - `register(ModCompat)` — ModList.get().isLoaded() 체크 → 액션 등록
  - `extendObservation()` — 모든 활성 compat에 관찰 데이터 확장 요청
- **테스트 가능성**: In-Game Only. ModList 의존
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음

### AE2Compat.java
- **역할**: Applied Energistics 2 호환 (stub). 미구현
- **의존성**: [AE2 필요] 현재 stub이므로 실제 의존 없음
- **핵심 로직**: 빈 액션 리스트 반환, extendObservation no-op
- **테스트 가능성**: N/A (stub)
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음 (의도적 stub)

### CreateCompat.java
- **역할**: Create 모드 호환 (stub). 미구현
- **의존성**: [Create 필요] 현재 stub이므로 실제 의존 없음
- **핵심 로직**: 빈 액션 리스트 반환, extendObservation no-op
- **테스트 가능성**: N/A (stub)
- **테스트 가능한 로직**: 없음
- **발견된 이슈**: 없음 (의도적 stub)

---

## 13. agent-runtime/src/ — TypeScript (7 files)

### index.ts
- **역할**: 에이전트별 Claude Agent SDK 쿼리 루프. maxTurns=50
- **의존성**: @anthropic-ai/claude-agent-sdk, mcp-server.ts (MCP 서버로 실행), prompt.ts, intervention.ts
- **핵심 로직**:
  - 환경변수에서 bridgePort, message, sessionId, agentName, isResume 읽기
  - `__dirname` 기반 MCP 서버 경로 결정 (src/ vs dist/)
  - `query()` async iterator로 SDK 응답 처리: assistant → thought/tool_call 로깅, result → 완료 로깅
  - 매 턴 `checkIntervention()` 호출 → intervention 있으면 로깅
  - 세션 연속성: isResume → resume 옵션, 아니면 sessionId 고정
- **테스트 가능성**: Integration (mock SDK + mock HTTP). 환경변수 기반 설정은 unit 테스트 가능
- **테스트 가능한 로직**: __dirname 기반 경로 결정, 환경변수 검증, 세션 모드 분기
- **발견된 이슈**:
  - `__dirname` 해석: `endsWith("src")` 체크로 src/ vs dist/ 구분 — "src"로 끝나는 다른 경로에서 오작동 가능
  - MCP 서버 경로 검증 없음: 파일이 없어도 에러 없이 SDK에 전달
  - 타임아웃 없음: query() 무한 대기 가능 (maxTurns=50이 유일한 제한)
  - manager-index.ts와 98% 코드 중복

### mcp-server.ts
- **역할**: MCP 서버. HTTP 브리지를 통해 Minecraft와 통신. 페르소나 기반 도구 필터링
- **의존성**: @modelcontextprotocol/sdk, zod, HTTP fetch
- **핵심 로직**:
  - `bridgeFetch(path, method, body)` — HTTP 브리지 호출. /actions는 전역, 나머지는 에이전트별 prefix
  - `isAllowed(toolName)` — AGENT_TOOLS 환경변수 기반 도구 필터링
  - 도구 등록: get_observation, execute_sequence, remember, recall, update_memory, forget, search_memory, send_message (항상 등록) + 조건부 등록 (move_to, mine_block, mine_area 등 — isAllowed 체크)
  - StdioServerTransport 연결
- **테스트 가능성**: Integration (mock HTTP). MCP 도구 등록/필터링은 독립 테스트 가능
- **테스트 가능한 로직**: 도구 필터링, bridgeFetch 경로 라우팅, Zod 스키마 검증
- **발견된 이슈**:
  - HTTP 에러 silent: `res.text()` — HTTP 4xx/5xx 응답도 그대로 반환. 에러 구분 없음
  - 타임아웃/재시도 없음: fetch()에 timeout 미설정 → Minecraft 서버 행 시 무한 대기
  - Zod 스키마: 파라미터 타입 검증만, 의미 검증(범위, 유효값) 미흡
  - 도구 설명 하드코딩: i18n 미지원 (prompt.ts와 동일)

### prompt.ts
- **역할**: 에이전트 시스템 프롬프트 빌더
- **의존성**: 없음 (순수 문자열 조합)
- **핵심 로직**:
  - `BASE_PROMPT` — 고정 규칙 + 도구 사용 가이드 + 메모리 시스템 설명
  - `buildSystemPrompt()` — BASE_PROMPT + 페르소나 내용 (AGENT_PERSONA_CONTENT) + 에이전트 이름
  - `SYSTEM_PROMPT` — 모듈 로드 시 1회 빌드 (export)
- **테스트 가능성**: Unit. 완전 독립 (순수 함수)
- **테스트 가능한 로직**: 프롬프트 빌드 정확성, 페르소나 주입, 빈 페르소나 처리
- **발견된 이슈**:
  - `SYSTEM_PROMPT`가 모듈 로드 시 1회 호출 — 환경변수 변경 시 반영 안 됨 (프로세스가 1회용이므로 실질 문제 없음)
  - 프롬프트 자체에 i18n 미적용 — 영어 고정

### intervention.ts
- **역할**: 에이전트 intervention 폴링 + 로그 전송
- **의존성**: fetch (Node.js 내장)
- **핵심 로직**:
  - `checkIntervention()` — GET /agent/{name}/intervention → message 반환 또는 null
  - `sendLog(type, message)` — POST /log → 전송 실패 시 무시
- **테스트 가능성**: Unit (fetch mock). 순수 HTTP 호출
- **테스트 가능한 로직**: 성공/실패/네트워크 에러 시 동작
- **발견된 이슈**:
  - catch 블록에서 에러 silent 무시 — 디버깅 어려움
  - 매 턴 폴링 — SDK 턴 수에 비례한 HTTP 요청 오버헤드
  - 재시도 없음: 일시적 네트워크 오류 시 intervention 누락

### manager-index.ts
- **역할**: Agent Manager Claude SDK 쿼리 루프. index.ts와 동일 구조
- **의존성**: @anthropic-ai/claude-agent-sdk, manager-mcp-server.ts, manager-prompt.ts
- **핵심 로직**: index.ts와 동일 — 환경변수명만 MANAGER_* 접두사
- **테스트 가능성**: Integration (mock SDK)
- **테스트 가능한 로직**: index.ts와 동일
- **발견된 이슈**:
  - **MEDIUM**: index.ts와 98% 코드 중복. checkIntervention, sendLog 함수도 인라인으로 재정의 — 공통 모듈 추출 필요
  - index.ts와 동일한 __dirname, 타임아웃, 검증 이슈

### manager-mcp-server.ts
- **역할**: Manager MCP 도구. 스케줄/관찰자/에이전트 CRUD
- **의존성**: @modelcontextprotocol/sdk, zod, HTTP fetch
- **핵심 로직**:
  - 스케줄 도구: create_schedule, update_schedule, delete_schedule, list_schedules, get_schedule
  - 관찰자 도구: add_observers, remove_observers, get_observer_states, get_supported_events
  - 에이전트 도구: list_agents, tell_agent, spawn_agent, despawn_agent
  - 월드 도구: get_world_time
- **테스트 가능성**: Integration (mock HTTP)
- **테스트 가능한 로직**: Zod 스키마 검증, 도구 등록 완전성
- **발견된 이슈**:
  - mcp-server.ts와 동일한 silent HTTP 에러 패턴
  - 검증 없음: 도구 응답 결과를 그대로 SDK에 전달

### manager-prompt.ts
- **역할**: Manager 시스템 프롬프트 (정적 상수)
- **의존성**: 없음
- **핵심 로직**: `MANAGER_SYSTEM_PROMPT` — 역할, 책임, 규칙, 스케줄 타입 설명, OBSERVER 좌표 규칙
- **테스트 가능성**: Unit. 상수 문자열
- **테스트 가능한 로직**: 없음 (정적 상수)
- **발견된 이슈**:
  - 게임 시각 값 하드코딩 (0=sunrise 등) — 프롬프트와 실제 게임 시간 매핑 불일치 시 업데이트 누락 위험
  - 구체적 사용 예시 없음 — LLM이 도구 사용법을 추론해야 함

---

## 14. agent-runtime/tests/ — TypeScript (6 files)

### mock-bridge.ts
- **역할**: 테스트용 mock HTTP 브리지. 기본 에이전트 라이프사이클 시뮬레이션
- **의존성**: http (Node.js 내장)
- **핵심 로직**: 관찰/액션/intervention 엔드포인트 mock 응답
- **테스트 가능성**: N/A (테스트 인프라)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**:
  - 커버리지 부족: 메모리 엔드포인트, 영역 작업, 에이전트별 라우팅 mock 없음

### test-bridge.ts
- **역할**: 실제 Minecraft 대상 통합 테스트. HTTP 브리지 직접 호출
- **의존성**: 실행 중인 Minecraft + agent-mod
- **핵심 로직**: 관찰/액션 HTTP 요청 → 응답 검증
- **테스트 가능성**: N/A (통합 테스트)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**:
  - Windows 경로 하드코딩 — 크로스 플랫폼 미지원

### test-mcp.ts
- **역할**: MCP 서버 도구 등록 테스트. 도구 목록 확인
- **의존성**: MCP 클라이언트
- **핵심 로직**: MCP 서버 시작 → listTools → 이름 확인
- **테스트 가능성**: N/A (테스트 파일)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**:
  - 도구 필터링 테스트 없음, 스키마 검증 없음, 에러 응답 테스트 없음

### test-integration.ts
- **역할**: 전체 체인 통합 테스트 (SDK → MCP → HTTP 브리지). 실제 Minecraft 필요
- **의존성**: 실행 중인 Minecraft + Claude SDK
- **핵심 로직**: query() 호출 → 응답 검증
- **테스트 가능성**: N/A (실시간 통합 테스트)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음

### test-mock-integration.ts
- **역할**: mock 브리지로 오프라인 전체 체인 테스트. 4개 시나리오
- **의존성**: mock-bridge.ts, Claude SDK
- **핵심 로직**: mock 브리지 시작 → SDK 쿼리 → 응답 검증
- **테스트 가능성**: N/A (테스트 파일)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**:
  - 커버리지 부족: 메모리, manager, persona, 에러 시나리오 미테스트

### test-sdk.ts
- **역할**: SDK 연결 정상 테스트. 최소한의 connectivity 검증
- **의존성**: Claude SDK
- **핵심 로직**: tools=[] 로 query() 호출 → 응답 확인
- **테스트 가능성**: N/A (테스트 파일)
- **테스트 가능한 로직**: N/A
- **발견된 이슈**: 없음 (의도적으로 최소한)

---

## Cross-Cutting Issues

### Critical (서버 크래시 / 데이터 손실)

| # | 이슈 | 위치 | 설명 |
|---|------|------|------|
| C1 | inventory.add() 반환값 미체크 | `CraftAction.java:104` | 인벤토리 가득 차면 재료 소모 후 결과물 소실 |
| C2 | 아이템 픽업 부분 손실 | `AgentTickHandler.java:70` | stack.copy() 후 전체 discard — add()가 부분 성공 시 나머지 수량 소실 |
| C3 | ScheduleConfig.fromJson() NPE | `ScheduleConfig.java:120` | "type" 키 누락 시 `obj.get("type")` → NPE. Type.valueOf() 잘못된 값 시 IllegalArgumentException |
| C4 | intervalTicks=0 → 0으로 나누기 | `ScheduleManager.java:199` | `elapsed % config.getIntervalTicks()` — intervalTicks=0이면 ArithmeticException |
| C5 | ObserverDef.fromJson() NPE | `ObserverDef.java:49` | x, y, z 좌표 키 누락 시 `obj.get("x")` → NPE. has() 체크 없음 |
| C6 | @reference 순환 참조 | `MemoryManager.java` | depth=3 제한으로 mitigated, 그러나 제한 도달 시 무한 재귀 아닌 depth 초과 중단은 검증 필요 |
| C7 | MemoryManager.getLocation() NPE | `MemoryManager.java` | Locatable.getLocation() null 반환 시 distanceTo() NPE |

### High (기능 오류)

| # | 이슈 | 위치 | 설명 |
|---|------|------|------|
| H1 | block.asItem() null | `PlaceBlockAction.java:54` | 대응 아이템 없는 블록에서 ForgeRegistries.ITEMS.getKey(null) → NPE |
| H2 | 컨테이너 거리 체크 없음 | `OpenContainerAction.java` | 어떤 거리에서든 컨테이너 열기 가능 |
| H3 | failureDetails no-op BUG | `UseItemOnAreaAction.java` | `asList().clear()` — JsonArray 내부 리스트에 반영 안될 수 있음 |
| H4 | runtimeProcess 비-volatile | `AgentContext.java:25` | daemon 스레드 set, 서버 스레드 read — Java 메모리 모델 위반 |
| H5 | PersonaConfig 대소문자/공백 | `PersonaConfig.java` | 섹션명은 lowercase이나 도구명은 원본 보존 → 대소문자 불일치 시 필터링 실패 |
| H6 | PathFollower 임계값 | `PathFollower.java:17` | WAYPOINT_THRESHOLD=0.3 — 이동 속도 대비 너무 작아 진동 가능 |

### Medium (성능 / 유지보수)

| # | 이슈 | 위치 | 설명 |
|---|------|------|------|
| M1 | O(n log n) 블록 스캔 | `ObservationBuilder.java:90-111` | 반경 8블록 (최대 4913 블록) 스캔 + sort — 매 관찰마다 실행 |
| M2 | triggered set 메모리 누적 | `ObserverManager.java:53` | threshold 미도달 상태에서 장기 실행 시 triggered Set 크기 무한 증가 |
| M3 | AgentLogger 스레드 안전 | `AgentLogger.java:30` | 여러 에이전트 동시 logAction → BufferedWriter 동시 쓰기 |
| M4 | TS 98% 코드 중복 | `index.ts` / `manager-index.ts` | checkIntervention, sendLog, query loop 전부 복사-붙여넣기 |
| M5 | TS HTTP 무방비 | `mcp-server.ts`, `intervention.ts` 등 | timeout, retry, connection pooling, 에러 구분 전부 없음 |
| M6 | 브로드캐스트 오버헤드 | `AgentAnimation.java` | 에이전트 수 × 플레이어 수 × 틱당 패킷 — 다중 에이전트에서 선형 증가 |
| M7 | ScheduleConfigTypeAdapter 로그 스팸 | `ScheduleConfigTypeAdapter.java:24` | 매 역직렬화마다 INFO 로그 — 프로덕션에서 불필요 |
