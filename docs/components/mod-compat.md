# Mod Compat Layer (모드 호환 레이어)

모드별 API를 Action/Observation으로 변환하는 플러그인 구조.

## 패키지 구조

```
com.pyosechang.agent.compat
├── ae2/       # Applied Energistics 2
├── create/    # Create
├── mekanism/  # Mekanism
└── ...        # 확장 가능
```

## 설계 원칙

각 compat 모듈은:
- 해당 모드가 설치된 경우에만 로드 (Optional Dependency)
- 자기만의 Action + Observation 세트를 등록
- 모드 API를 직접 호출 (리버스 엔지니어링 불필요)

```java
// 로드 예시
if (ModList.get().isLoaded("ae2")) {
    AE2Compat.register();  // AE2 Action + Observation 등록
}
```

## 새 모드 추가 방법

1. `compat/<modid>/` 패키지 생성
2. 해당 모드의 Action 세트 정의
3. 해당 모드의 Observation 확장 정의
4. `build.gradle`에 optional dependency 추가
5. 로드 조건 등록
