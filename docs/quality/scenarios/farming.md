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

### 평가 관점

1. **목표 달성** — 호미를 크래프트하고 실제로 경작했는가? farmland가 생성되었는가?
2. **크래프트 체인** — 판자→막대→작업대→호미 순서를 정확히 수행했는가? 불필요한 중간 단계 없이?
3. **도구 선택** — use_item_on_area를 사용했는가? 개별 use_item_on 반복은 비효율.
4. **행동 효율** — execute_sequence 활용 여부. equip 중복 호출이 없었는가?
5. **실패 복구** — 경작 실패(이미 경작된 땅, 잘못된 블록) 시 적절히 대응했는가?

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

### 평가 관점

1. **목표 달성** — 상자에서 씨앗을 꺼내 farmland에 심었는가? wheat_crops가 존재하는가?
2. **컨테이너 조작** — open_container → click_slot → close_container 순서가 정확한가?
3. **도구 선택** — use_item_on_area 사용 여부. 넓은 밭에 개별 use_item_on은 비효율.
4. **행동 효율** — equip 반복 횟수. 씨앗 소진 감지 (빈 손으로 계속 심기 시도하지 않는가?)
5. **실패 복구** — farmland가 아닌 블록에 심기 실패 시 적절히 건너뛰는가?
