# Gathering Scenarios

## S-G001: 나무 채벌 (맨손)

- **전제조건**: 에이전트 근처에 나무(oak log), 인벤토리 비어있음
- **명령**: "근처 나무 캐"
- **측정 기준**:
  - 턴 수, 비용
  - `mine_area` 사용 여부 (vs `mine_block` 반복)
  - 자동 아이템 흡수 작동 여부 (`pickup_items` 수동 호출 없이)
  - 채굴 애니메이션 (lookAt + swingArm)
- **관련 컴포넌트**: `actions`, `brain`, `body`
