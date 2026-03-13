# Body Component — Problem Report

FakePlayer → ServerPlayer 전환 시 함께 해결해야 할 body 관련 이슈 목록.

---

## 근본 원인

현재 에이전트는 `FakePlayer`(Forge) 기반이며, 서버의 `PlayerList`에 등록되지 않아 `doTick()`이 호출되지 않음. 바닐라 `ServerPlayer`가 자동으로 받는 물리/렌더링 처리가 전부 누락된 상태.

---

## P1 — Player-unlike (물리 법칙 위반)

FakePlayer라서 발생하는 문제. ServerPlayer 전환 시 자동 해결이 기대되는 항목들.

### 1. 중력 없음
- **현상**: 에이전트가 공중에 떠 있어도 떨어지지 않음
- **원인**: `doTick()` 미호출 → `travel()` 미실행 → 중력 계산 스킵
- **기대**: ServerPlayer의 `tick()` → `travel()` → 자동 낙하

### 2. 블록 충돌 없음 (겹침)
- **현상**: 에이전트가 블록을 통과하여 겹쳐질 수 있음
- **원인**: `move()` 호출이 없어 collision detection 미작동
- **현재 코드**: `PathFollower.tick()`에서 `setPos()` 직접 호출 — 충돌 체크 우회
- **기대**: ServerPlayer 전환 후 `move()` 기반 이동으로 변경하면 해결
- **주의**: PathFollower가 `setPos()` 대신 `move()` 또는 모션 기반 이동으로 전환 필요

### 3. 장착 아이템 미표시
- **현상**: 에이전트가 들고 있는 아이템이 다른 플레이어에게 보이지 않음
- **원인**: FakePlayer의 장비 변경이 클라이언트에 브로드캐스트되지 않음
- **관련 패킷**: `ClientboundSetEquipmentPacket`
- **기대**: ServerPlayer가 PlayerList에 등록되면 장비 변경이 자동 동기화될 가능성 높음
- **검증 필요**: 자동 동기화 안 되면 `EquipAction`/`AgentTickHandler`에서 수동 브로드캐스트 추가

---

## P2 — 위치 브로드캐스트 의존성

### 4. 위치 동기화가 lookAt()에 의존
- **현상**: 에이전트 이동이 클라이언트에 보이려면 `AgentAnimation.lookAt()` 호출이 필요
- **원인**: `PathFollower.tick()`은 서버사이드 `setPos()`만 호출. 별도 위치 패킷 브로드캐스트 없음. `lookAt()`의 `ClientboundTeleportEntityPacket`에 위치가 부수적으로 포함되어 전달됨
- **기대**: ServerPlayer가 PlayerList에 등록되면 위치 변경이 자동으로 추적/브로드캐스트됨
- **관련 파일**: `PathFollower.java`, `AgentAnimation.java`

---

## P3 — Human-unlike (행동 부자연스러움)

물리 전환만으로는 해결되지 않는 항목들. ServerPlayer 전환 후 별도 작업 필요.

### 5. 블록 설치 시 face 방향 미고려
- **현상**: chest, 화로 등 설치 시 주변 환경(접근 가능성, 플레이어 위치)을 고려한 face 방향 선택을 하지 않음
- **해결 방향**: Action 휴리스틱 (UseItemOnAction에서 placeable 블록일 때 최적 face 자동 계산) + 프롬프트 가이드
- **우선순위**: P1 해결 후 진행

### 6. move_to entity 시 거리 미확보
- **현상**: "나한테 와" → 플레이어 정확한 좌표로 이동 (겹침)
- **해결 방향**: move_to 대상이 entity일 때 1~2블록 거리를 두고 정지 + 대상을 바라보기
- **우선순위**: P1 해결 후 진행

---

## 전환 시 확인 체크리스트

ServerPlayer 전환 후 아래 항목 검증:

- [ ] 공중에서 스폰 → 자동 낙하하는지
- [ ] 벽/블록 방향으로 이동 → 겹치지 않고 막히는지
- [ ] 아이템 equip 후 → 다른 플레이어에게 보이는지
- [ ] 이동 시 → lookAt() 없이도 위치가 클라이언트에 동기화되는지
- [ ] PathFollower가 `setPos()` → `move()` 전환 후 정상 동작하는지
- [ ] 기존 액션들(mine, craft, use_item_on 등)이 정상 동작하는지

---

## 관련 파일

| 파일 | 역할 | 전환 시 영향 |
|------|------|-------------|
| `AgentManager.java` | 스폰/디스폰 (FakePlayerFactory 사용) | **핵심 변경 대상** |
| `AgentTickHandler.java` | 매 틱 action tick + 아이템 픽업 | ServerPlayer tick과 중복 가능성 검토 |
| `AgentAnimation.java` | lookAt/swingArm 패킷 브로드캐스트 | ServerPlayer면 일부 자동화 가능 |
| `PathFollower.java` | 틱 기반 setPos() 이동 | `move()` 기반으로 전환 필요 |
| `AgentContext.java` | FakePlayer 참조 보유 | 타입 변경 (FakePlayer → ServerPlayer) |
| `core/action/*.java` | 모든 액션이 FakePlayer 파라미터 사용 | 타입 변경 전파 |
