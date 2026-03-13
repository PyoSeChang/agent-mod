# Farming Scenarios

## S-F001: 밭 일구기 (나무→호미→경작)

- **전제조건**: 에이전트 근처에 나무 + 흙 블록, 인벤토리 비어있음
- **명령**: "나무 캐서 호미 만들고 주변 땅 경작해"
- **측정 기준**:
  - 턴 수, 비용
  - craft 성공 여부 (판자→막대→작업대→호미)
  - 경작 성공률 (farmland 변환 수)
  - `use_item_on_area` 사용 여부 (vs 개별 `use_item_on`)
  - `execute_sequence` 사용 여부
- **관련 컴포넌트**: `actions`, `brain`

---

## S-F002: 씨앗 심기 (상자→심기)

- **전제조건**: 에이전트 근처에 씨앗이 든 상자 + farmland
- **명령**: "상자에서 씨앗 꺼내서 밭에 심어"
- **측정 기준**:
  - 턴 수, 비용
  - 심기 성공률 (wheat_crops 생성 수 / farmland 수)
  - `use_item_on_area` 사용 여부 + 성공률
  - `execute_sequence` 사용 여부
  - equip 반복 횟수 (많을수록 비효율)
- **관련 컴포넌트**: `actions`, `brain`
