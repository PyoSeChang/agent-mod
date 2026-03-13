# Composite Scenarios

여러 카테고리를 결합하는 end-to-end 시나리오.

## S-X001: 농사 전체 (채벌→호미→경작→심기)

- **전제조건**: 에이전트 근처에 나무 + 흙 + 씨앗 든 상자
- **명령**: 2단계로 실행
  1. "나무 캐서 호미 만들고 주변 땅 경작해"
  2. "상자에서 씨앗 꺼내서 심어"
- **측정 기준**:
  - 총 턴 수 (두 런 합산), 총 비용
  - 전체 성공 여부 (farmland 위에 wheat_crops 존재)
  - 새 도구 활용도: mine_area, use_item_on_area, execute_sequence 사용 횟수
  - 불필요 액션 수: 중복 get_observation, 수동 pickup_items, 반복 equip
- **관련 컴포넌트**: `actions`, `brain`, `body`
