# body — CHANGELOG

에이전트의 **시각적/물리적 존재감** (애니메이션, 틱 처리).
Key files: `AgentAnimation`, `AgentTickHandler`

---

## v0.3.0 — 2026-03-13

멀티 에이전트 대응.

- `AgentTickHandler`: 단일 에이전트 → `AgentManager.getAllAgents()` 순회
- `AgentAnimation`: AgentManager 참조로 전환

---

## v0.2.2 — 2026-03-12

이동 시 시선 수평화.

- `MoveToAction`: lookAt Y를 `waypoint.getY() + 0.5` → `agent.getEyeY()` (시선 수평화, I-006)
- `UseItemOnAreaAction`: 이동 중 시선도 동일하게 수평화

---

## v0.2.1 — 2026-03-12

머리 회전 동기화.

- `AgentAnimation.lookAt()`: `ClientboundRotateHeadPacket` 추가 (머리 회전 동기화)

---

## v0.2.0 — 2026-03-11

애니메이션 + 자동 아이템 흡수.

- `AgentAnimation` 유틸리티 신규: lookAt (yaw/pitch), swingArm 패킷 브로드캐스트
- `AgentTickHandler`: 매 틱 2블록 반경 아이템 자동 흡수

---

## v0.1.0 — 2026-03-11

초기 구현.

- FakePlayer 스폰/디스폰 + 클라이언트 가시성 패킷
