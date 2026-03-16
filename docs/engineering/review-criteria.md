 # Code Review Criteria — agent-mod

> 도출 일자: 2026-03-15
> 근거: Phase 1 전수 조사 (Java 69개 + TypeScript 7개 소스 + 5개 테스트)

## 개요

이 문서는 전수 조사에서 도출된 프로젝트 특화 코드 리뷰 체크리스트입니다.
일반적인 코드 리뷰 기준이 아닌, 이 프로젝트에서 실제로 발견된 패턴에 기반합니다.

## 심각도 정의

- **CRITICAL**: 서버 크래시, 데이터 손실, 무한 루프. 즉시 수정 필요
- **HIGH**: 기능 오류, 보안 취약점. 다음 릴리스 전 수정
- **MEDIUM**: 성능 문제, 유지보수 부담. 계획적 수정
- **LOW**: 코드 품질, 일관성. 리팩토링 시 수정

## 카테고리별 체크리스트

### 1. Null 안전성

#### 체크 항목

- [ ] Minecraft registry 조회 결과 (ForgeRegistries.*.getKey()) null 체크
- [ ] Inventory.add() 반환값 체크 (false = 인벤토리 가득참 → 아이템 손실)
- [ ] block.asItem() null 체크 (일부 블록은 아이템 없음)
- [ ] Locatable.getLocation() null 체크 (Locatable이어도 location이 null일 수 있음)
- [ ] JSON 파싱에서 obj.get("field") null 체크 (has() 선행 필요)
- [ ] 싱글톤의 서버 참조 (AgentManager.getServer()) null 체크

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| CraftAction | inventory.add(resultStack) 반환값 미체크 (L104) → 인벤토리 풀이면 아이템 손실 | CRITICAL |
| AgentTickHandler | pickupNearbyItems에서 add() 반환값 체크하지만 원본 stack.copy()로 처리 — 부분 add 시 일부 손실 가능 (L70) | CRITICAL |
| PlaceBlockAction | block.asItem() null 미체크 (L54) → Bedrock 등 아이템 없는 블록에서 NPE | HIGH |
| MemoryManager | getAllForTitleIndex에서 Locatable 체크 후 getLocation() null 가드 존재 (L332), 하지만 구버전 코드 경로에서 누락 가능 | HIGH |
| ObserverDef | fromJson()에서 x, y, z에 has() 체크 없음 (L49-51) → 잘못된 JSON 입력 시 NPE | HIGH |
| AgentManager | spawn()에서 server 필드 null 체크 없이 직접 사용 (L54) → setServer() 호출 전 spawn 시 NPE | HIGH |
| AgentLogger | sanitizeParams(null)에서 params.deepCopy() 호출 (L143) → logAction에 null params 전달 시 NPE | MEDIUM |

### 2. 스레드 안전성

#### 체크 항목

- [ ] ConcurrentHashMap 순회 중 수정 (ConcurrentModificationException)
- [ ] 싱글톤 필드의 volatile/synchronized 여부
- [ ] Process 참조 TOCTOU (null 체크 → 사용 사이 race)
- [ ] BufferedWriter 동시 접근
- [ ] CompletableFuture double-complete 방지

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| ScheduleManager | tick()에서 scheduleCache.values() 순회 (L165) — ConcurrentHashMap의 weakly consistent iterator이므로 CME는 안 나지만, 순회 중 create/delete가 tick 결과를 왜곡 가능 | CRITICAL |
| AgentContext | runtimeProcess 필드 비동기 접근: isRuntimeRunning()이 null 체크 후 isAlive() 호출 (L62) — 두 호출 사이 다른 스레드에서 null 설정 시 NPE | HIGH |
| AgentLogger | BufferedWriter를 synchronized 없이 사용 (L127-136) — 여러 에이전트가 동시에 logAction 호출 시 interleaved write → 파일 손상 | HIGH |
| ObserverManager | processEvent에서 registry 내부의 ArrayList를 동시 수정 가능 (L164, L73) — registry는 ConcurrentHashMap이지만 내부 List<ObserverRegistration>은 ArrayList | HIGH |
| AgentManager | spawn()에서 check-then-act: containsKey() 후 put() 사이 race (L48) — 동시 spawn 호출 시 중복 에이전트 | HIGH |
| ManagerContext | runtimeProcess TOCTOU: isRuntimeRunning()과 getRuntimeProcess() 분리 호출 (L32-33) | MEDIUM |
| AsyncAction 구현체들 | tick()과 cancel() 동시 호출 시 future.complete() 두 번 호출 가능 — CompletableFuture 자체는 두 번째 complete를 무시하지만 active 플래그 race는 존재 | MEDIUM |

### 3. 에러 처리

#### 체크 항목

- [ ] reflection 실패 시 fallback 또는 명시적 예외 전파 (silent failure 금지)
- [ ] IOException catch 후 적절한 복구/사용자 알림
- [ ] valueOf()/parseInt() 에 대한 예외 처리
- [ ] HTTP fetch 실패 시 에러 전파 (TS: silent error swallowing 패턴)
- [ ] 프로세스 시작/종료 실패 처리

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| ScheduleConfig | fromJson()에서 Type.valueOf(typeStr) (L120) — 잘못된 type 문자열 시 uncaught IllegalArgumentException → 호출자 크래시 | CRITICAL |
| ActionRegistry | registerAsync()에서 reflection 실패 시 LOGGER.error만 호출 (L47) → 해당 액션이 등록 안 되어 이후 get() 시 null → NPE 체인 | HIGH |
| AgentNetHandler | createMockConnection()에서 channel reflection 실패 시 LOGGER.error만 (L57) → 이후 ServerPlayer.tick()에서 패킷 전송 시 NPE | HIGH |
| AgentManager | loadInventory()에서 IOException catch 후 빈 인벤토리로 스폰 (L219-221) → 아이템 보유 에이전트가 빈손으로 나타나지만 사용자에게 알림 없음 | MEDIUM |
| TS mcp-server.ts | bridgeFetch()에서 HTTP 에러를 별도 처리 안 함 (L20-31) → res.ok 미체크, 에러 응답 텍스트가 그대로 에이전트에 전달 | HIGH |
| TS intervention.ts | sendLog() 실패 시 catch 블록이 비어 있음 (L22-25) — 의도적이지만 로그 누락 디버깅 불가 | MEDIUM |

### 4. 리소스 관리

#### 체크 항목

- [ ] 프로세스 종료 보장 (zombie process 방지)
- [ ] 파일 핸들 해제 (writer close 보장)
- [ ] 로그 파일 rotation/cleanup
- [ ] ObserverManager triggered set 크기 제한 (memory leak 방지)
- [ ] BlockPos 반복 생성 방지 (캐시 또는 좌표 직접 사용)

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| ObserverManager | triggeredMap에 TTL/max-size 제한 없음 (L53) — 스케줄 삭제 후에도 unregisterSchedule()에서 제거되지만, 높은 threshold + 느린 트리거 시 set이 계속 증가 | HIGH |
| AgentLogger | 로그 파일 영구 축적 — rotation/cleanup 로직 없음 (L41-45) — 장시간 운영 시 디스크 소진 | MEDIUM |
| RuntimeManager | 프로세스 zombie 위험: finally 블록에서 setRuntimeProcess(null) 하지만 (L111), process.destroy()나 waitFor()는 별도 스레드에서만 — JVM 종료 시 orphan 가능 | MEDIUM |
| ObserverDef | getBlockPos() 매 호출마다 new BlockPos() 생성 (L33) — matchesCondition 등에서 빈번 호출되나 GC 부담은 낮음 | LOW |

### 5. 입력 검증

#### 체크 항목

- [ ] ScheduleConfig timeOfDay 범위 (0-23999)
- [ ] ScheduleConfig intervalTicks > 0 (0이면 division by zero)
- [ ] PERSONA.md 파서: section 이름 대소문자 통일
- [ ] PERSONA.md 파서: 도구명/acquaintance 이름 trim
- [ ] ResourceLocation 형식 검증
- [ ] 좌표 범위 검증 (Y < 320, Y > -64)

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| ScheduleConfig | intervalTicks=0 허용 — checkInterval()에서 elapsed % 0 → ArithmeticException (L199) | CRITICAL |
| ScheduleConfig | timeOfDay 범위 검증 없음 (L129) — 음수나 24000 이상 값 허용 → checkTimeOfDay()에서 절대 매칭 안 됨 (기능 오류) | HIGH |
| PersonaConfig | parseToolsList()에서 도구명 trim 후 빈 문자열만 제거 (L157-159) — 공백 포함 도구명은 isAllowed() 매칭 실패 | MEDIUM |
| TS mcp-server.ts | Zod 스키마에 좌표 범위 검증 없음 — z.number()만 사용 (L123 등) — 극단값이 서버에 전달 | LOW |

### 6. 일관성

#### 체크 항목

- [ ] 거리 검증: 모든 액션에 적절한 거리 제한 존재
- [ ] JSON 응답 형식: {ok: true/false, error: "msg"} 통일
- [ ] 애니메이션: swingArm() 호출 일관성 (AttackAction O, InteractAction X)
- [ ] 액션 인터페이스: throws 선언 통일

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| OpenContainerAction | 거리 검증 누락 (L23-35) — 다른 블록 액션은 4.5-6.0 체크하지만 OpenContainer는 무제한 거리에서 컨테이너 열기 가능 | HIGH |
| InteractAction | swingArm() 없음 (L46-48) — AttackAction은 lookAt+swingArm 호출 (L51-52), InteractAction은 lookAt만 | LOW |
| ClickSlotAction | 미등록 clickType → default PICKUP (L26) — 오타 입력 시 silent fallback, 에러 반환이 더 안전 | LOW |

### 7. 성능

#### 체크 항목

- [ ] 매 틱 반복 작업의 복잡도 (블록 스캔, 패킷 브로드캐스트)
- [ ] 캐시 활용 (distance 재계산, agentPrefix 문자열)
- [ ] 네트워크 쓰로틀링 (per-tick broadcast 제한)
- [ ] TS fetch 연결 풀링/keep-alive

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| ObservationBuilder | 17x17x17=4913 블록 스캔 per observation (L94-107) — radius=8 → 실제 diameter=17, 매 observation 요청마다 전체 스캔 | HIGH |
| MoveToAction | broadcastPosition()에서 매 틱 모든 플레이어에 teleport 패킷 전송 (L166-174) — AgentTickHandler에서 이미 20틱마다 동기화 (L51-52) 하므로 이중 전송 | MEDIUM |
| UseItemOnAreaAction | tickNextPos()에서 매 틱 distanceTo 재계산 (L143) — 위치 변경 없을 때도 계산 | LOW |
| TS intervention.ts | 매 SDK 턴(=매 Claude API 응답)마다 HTTP polling (index.ts L76) — 턴 수 많을 때 불필요한 요청 | MEDIUM |
| AgentAnimation | broadcast()에서 모든 에이전트의 모든 패킷을 전체 플레이어에 전송 (L51-56) — 거리 기반 필터 없음, 멀리 있는 플레이어에게도 전송 | MEDIUM |

### 8. Minecraft / Forge 모드 개발 관행

#### 체크 항목

- [ ] **서버-클라이언트 분리**: 서버 전용 로직에 클라이언트 클래스 참조 없음; `@OnlyIn(Dist.CLIENT)` 올바르게 사용
- [ ] **스레드 컨텍스트**: 월드 변경은 서버 스레드에서만; 크로스 스레드 시 `server.execute()` 사용
- [ ] **엔티티 생존 확인**: 접근 전 `entity.isAlive()` / `entity.isRemoved()` 체크
- [ ] **레지스트리 접근 타이밍**: `ForgeRegistries.*` 사용은 등록 단계 이후; 모드 생성자에서 레지스트리 접근 금지
- [ ] **이벤트 버스**: `@SubscribeEvent` 메서드가 올바른 이벤트 페이즈(PRE/POST) 처리
- [ ] **NBT 안전성**: `CompoundTag` 필드 읽기 전 `tag.contains()` 체크
- [ ] **레벨 사이드 체크**: `level.isClientSide` 확인하여 중복 실행 방지
- [ ] **틱 성능**: 무거운 연산은 매 틱 실행하지 않고 지연/주기 제한 (rate-limit)
- [ ] **패킷 크기**: 커스텀 패킷 32KB 이하; 큰 데이터는 청크 분할
- [ ] **ResourceLocation 형식**: 유효한 namespace:path (`[a-z0-9_.-]+:[a-z0-9/._-]+`)
- [ ] **모드 호환성**: 선택적 의존성은 `ModList.get().isLoaded()` 가드 사용
- [ ] **난독화 대응**: reflection은 SRG/intermediary 이름 사용 또는 타입 기반 fallback
- [ ] **ServerPlayer 안전성**: `connection` null 체크 후 `send()`; 플레이어 제거 확인 후 조작
- [ ] **GameRule 존중**: 월드 변경 시 게임 규칙(mobGriefing, keepInventory 등) 준수

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| AgentPlayer | `detectEquipmentUpdates()` reflection이 SRG name "channel" 또는 타입 기반 fallback 사용 — Forge 버전 변경 시 silent failure (L33-45) | HIGH |
| AgentNetHandler | `Connection.channel` reflection — 난독화 변경 시 EmbeddedChannel 설정 실패, 이후 패킷 NPE (L57) | HIGH |
| AgentTickHandler | `onServerTick()` 예외 발생 시 다른 에이전트 tick 중단 — 개별 에이전트 try-catch 미적용 (L33-60) | MEDIUM |
| AgentAnimation | `broadcast()` 거리 기반 필터 없이 모든 플레이어에 패킷 전송 — 50인 서버에서 성능 이슈 (L51-56) | MEDIUM |
| AgentCommand | 에이전트 이름 path traversal 미검증 — `../../../` 포함 이름 가능 (L122) | MEDIUM |
| ObserverManager | `@SubscribeEvent` 핸들러에서 `serverLevel.getServer().execute()` 사용 — 다음 틱 지연 (bonemeal) (L261-270) | LOW |

### 9. 설계/아키텍처

#### 체크 항목

- [ ] 코드 중복: 동일 로직이 여러 파일에 반복되는지
- [ ] Dead code: 사용되지 않는 메서드/필드
- [ ] 순환 의존성: 패키지 간 circular import
- [ ] 싱글톤 테스트 가능성: reset 메서드 또는 의존성 주입

#### 발견된 기존 이슈

| 파일 | 이슈 | 심각도 |
|------|------|--------|
| TS index.ts ↔ manager-index.ts | 98% 코드 중복 — checkIntervention(), sendLog(), main() 루프, 에러 처리 모두 동일 구조. manager-index.ts가 intervention.ts를 import하지 않고 인라인 복사 | HIGH |
| PersonaConfig | saveParsedSection() dead code (L146-151) — 빈 메서드, parseAllSections()로 대체됨. parseContent()에서 호출하지만 결과를 parseAllSections()가 덮어씀 (L65-107) | MEDIUM |
| ScheduleMemory ↔ ScheduleConfig | memory 패키지의 ScheduleMemory가 schedule 패키지의 ScheduleConfig를 필드로 보유, schedule 패키지의 ScheduleManager가 memory 패키지의 MemoryManager를 사용 → 순환 의존 | LOW |
| AgentContext | stoppedByUser 필드 (L24) — RuntimeManager.stop()에서만 true로 설정 (L205), RuntimeManager.launch()에서 체크 (L87) 하지만 실질적으로 세션 리셋 여부만 결정 — 의도는 유효하나 이름이 모호 | LOW |
| UseItemOnAreaAction | failureDetails.asList().clear() (L109) — JsonArray.asList()는 unmodifiable view를 반환하므로 clear()가 UnsupportedOperationException 발생. 실제로는 start()마다 새 인스턴스를 생성하지 않고 필드 재사용 시 이전 데이터 잔존 | HIGH |

## 전체 이슈 카탈로그 (심각도별)

### CRITICAL (즉시 수정)

1. **CraftAction L104**: `inventory.add(resultStack)` 반환값 미체크 — 인벤토리 가득 참 상태에서 크래프팅하면 결과 아이템이 소멸. 재료는 이미 소비된 후이므로 아이템 손실 확정.
2. **AgentTickHandler L70**: `pickupNearbyItems`에서 `agent.getInventory().add(stack.copy())` — add()가 false 반환(인벤토리 풀) 시에도 `itemEntity.discard()` 호출. copy()를 사용하므로 부분 add는 원본에 미반영.
3. **ScheduleManager L165**: `scheduleCache.values()` 순회 중 `create()`/`delete()`에 의한 수정 가능 — ConcurrentHashMap iterator는 CME를 던지지 않지만, tick 평가 중 추가/삭제된 스케줄의 일관성이 깨질 수 있음.
4. **ScheduleConfig L199**: `intervalTicks=0` 허용 — `elapsed % config.getIntervalTicks()`에서 `ArithmeticException: / by zero`. fromJson()이나 setter에서 최솟값 검증 없음.
5. **ScheduleConfig L120**: `Type.valueOf(obj.get("type").getAsString())` — 잘못된 type 문자열 시 uncaught `IllegalArgumentException`. JSON 파일이 수동 편집되거나 버전 불일치 시 서버 크래시.

### HIGH (다음 릴리스 전)

1. **PlaceBlockAction L54**: `block.asItem()` null 미체크 — `ForgeRegistries.ITEMS.getKey()` 에 null 전달 시 NPE. Bedrock, Barrier 등 아이템 없는 블록에서 발생.
2. **ObserverDef L49-51**: `fromJson()`에서 `obj.get("x").getAsInt()` — `has()` 체크 없음. x/y/z 필드가 누락된 JSON 입력 시 NPE.
3. **AgentManager L54**: `spawn()`에서 `server` 필드 null 체크 없이 `new AgentPlayer(server, ...)` 호출. `setServer()` 호출 전 spawn 시 NPE.
4. **AgentContext L62**: `runtimeProcess != null && runtimeProcess.isAlive()` — 두 조건 평가 사이에 다른 스레드에서 `setRuntimeProcess(null)` 호출 시 NPE. volatile 아님.
5. **AgentLogger L127-136**: `BufferedWriter` synchronized 없이 사용. 여러 에이전트가 동시에 `logAction()` 호출 시 write interleaving → JSONL 줄 경계 손상.
6. **ObserverManager L72-74**: `registry` 내부의 `ArrayList<ObserverRegistration>` — ConcurrentHashMap의 value인 Map의 value가 ArrayList. `processEvent()`에서 순회(L164) 중 `registerSchedule()`에서 add(L74) 시 ConcurrentModificationException.
7. **AgentManager L48**: `spawn()` check-then-act — `containsKey(name)` → `agents.put(name, ctx)` 사이에 다른 스레드에서 동일 이름 spawn 가능. 중복 AgentPlayer 생성.
8. **ScheduleConfig L129**: `timeOfDay` 범위 검증 없음 — 음수, 24000 이상 허용. `checkTimeOfDay()`에서 `tickInDay != config.getTimeOfDay()` 비교에 절대 매칭되지 않아 스케줄이 영원히 트리거 안 됨.
9. **ActionRegistry L47**: `registerAsync()` reflection 실패 시 LOGGER.error만 → 해당 액션이 `asyncFactories`에 미등록 → 이후 `get()` → `createAsync()` → null 반환 → 호출자에서 NPE.
10. **AgentNetHandler L57**: `createMockConnection()`에서 channel reflection 완전 실패 시 LOGGER.error만 → `ServerPlayer.tick()` 내부에서 `Connection.channel` 접근 시 NPE.
11. **ObserverManager L53**: `triggeredMap` 무한 증가 가능 — 스케줄 삭제 시 `unregisterSchedule()`에서 제거되지만, threshold에 도달하지 않는 스케줄의 triggered set은 계속 누적.
12. **TS mcp-server.ts L20-31**: `bridgeFetch()`에서 `res.ok` 미체크 — HTTP 4xx/5xx 응답이 에러 텍스트 그대로 에이전트에 전달. 에이전트가 에러 메시지를 데이터로 오해.
13. **OpenContainerAction L23-35**: 거리 검증 누락 — 다른 블록 대상 액션(PlaceBlock=6.0, UseItemOn=4.5)은 거리 제한이 있지만, OpenContainer는 무제한 거리에서 컨테이너를 열 수 있음.
14. **UseItemOnAreaAction L109**: `failureDetails.asList().clear()` — `JsonArray.asList()`는 backing list를 반환하지만 Gson 버전에 따라 UnsupportedOperationException. failureDetails가 final 필드이므로 재사용 시 이전 실행의 데이터 잔존.
15. **TS index.ts ↔ manager-index.ts**: 98% 코드 중복 — `checkIntervention()`, `sendLog()`, `main()` 루프 구조, 에러 처리 모두 복사-붙여넣기. 버그 수정 시 양쪽 동기화 누락 위험.

### MEDIUM (계획적 수정)

1. **AgentLogger L143**: `sanitizeParams(null)` 시 `params.deepCopy()` → NPE. `logAction()` 호출 시 params가 null일 수 있는 경로 존재.
2. **ManagerContext L32-33**: `isRuntimeRunning()`에서 TOCTOU — `runtimeProcess` 필드가 volatile 아니므로 가시성 문제 + null 체크와 isAlive() 사이 race.
3. **AsyncAction 구현체들**: `tick()`과 `cancel()` 동시 호출 시 `future.complete()` 두 번 호출 — `CompletableFuture` 자체는 두 번째를 무시하지만, `active` 플래그 변경과 future complete 사이에 ordering 보장 없음.
4. **AgentManager L219-221**: `loadInventory()` IOException → 빈 인벤토리로 스폰. 기존 아이템 데이터가 존재하지만 읽기 실패 시 아이템 전부 소실되며 사용자에게 알림 없음.
5. **AgentLogger L41-45**: 로그 파일 rotation 없음 — 세션마다 `.jsonl` 파일 생성, 삭제 로직 없음. 장시간 운영 시 디스크 소진.
6. **RuntimeManager L111**: 프로세스 zombie — finally에서 `setRuntimeProcess(null)` 하지만, JVM 비정상 종료 시 Node.js 프로세스가 orphan으로 잔류.
7. **PersonaConfig L157-159**: `parseToolsList()`에서 도구명 trim 적용 — 하지만 PERSONA.md에서 `- move_to ` (trailing space) 작성 시 trim 후 `"move_to"`가 되므로 문제없음. 다만 `- move _to` 같은 내부 공백은 필터링 안 됨.
8. **MoveToAction L166-174**: `broadcastPosition()` 매 틱 실행 — AgentTickHandler가 20틱마다 동기화 패킷 전송(L51-52). move_to 진행 중에는 MoveToAction이 매 틱, AgentTickHandler가 20틱마다 → 이중 전송.
9. **TS intervention.ts L22-25**: `sendLog()` 실패 시 silent catch — 의도적이지만 로그 전달 실패 디버깅이 어려움.
10. **TS intervention.ts L7 (index.ts L76)**: 매 SDK 턴마다 intervention HTTP polling — 빈번한 턴에서 불필요한 HTTP 요청 발생.
11. **AgentAnimation L51-56**: `broadcast()` 모든 플레이어에 무조건 패킷 전송 — 거리 기반 필터 없음. 서버에 50명 접속 + 5 에이전트 = 매 lookAt당 250 패킷.
12. **PersonaConfig L146-151**: `saveParsedSection()` dead code — 빈 메서드. `parseContent()` L78에서 호출하지만 아무 동작 안 함, 결과를 `parseAllSections()` L106이 덮어씀.

### LOW (리팩토링 시)

1. **ObserverDef L33**: `getBlockPos()` 매 호출마다 `new BlockPos(x, y, z)` — 빈번한 호출이 아니므로 즉각적 성능 영향은 미미하나, 캐싱하면 할당 감소.
2. **InteractAction L46-48**: `lookAt()` 호출하지만 `swingArm()` 미호출 — AttackAction(L51-52)은 lookAt+swingArm 모두 호출. 상호작용 시 시각적 피드백 누락.
3. **ClickSlotAction L26**: 미등록 `clickType` → default `ClickType.PICKUP` — 오타 입력("pckup") 시 silent fallback. 에이전트가 의도와 다른 동작 수행.
4. **ScheduleMemory ↔ ScheduleConfig**: memory 패키지와 schedule 패키지 사이 순환 의존 — `ScheduleMemory`가 `ScheduleConfig`를 필드로 보유, `ScheduleManager`가 `MemoryManager`를 사용. 현재 기능 문제는 없으나 패키지 분리 원칙 위반.
5. **AgentContext L24**: `stoppedByUser` 필드 — RuntimeManager에서만 사용. 세션 리셋 여부 결정에만 관여. 기능적으로는 유효하나 이름이 모호하고 AgentContext의 책임 범위가 불명확.
6. **UseItemOnAreaAction L143**: `tickNextPos()`에서 매 틱 `distanceTo()` 재계산 — 에이전트가 정지 상태일 때도 계산. 캐싱으로 최적화 가능하나 실제 성능 영향은 미미.
7. **TS mcp-server.ts L123 등**: Zod 스키마에 좌표 범위 검증 없음 — `z.number()`만 사용. 극단값(Y=10000)이 서버에 전달되어 무의미한 연산 발생 가능.
