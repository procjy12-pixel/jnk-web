# JNK 필터 (안드로이드)

틸앤오렌지 · 라이카 감성 사진 필터 앱. 외부 라이브러리 없이 Kotlin + 안드로이드 기본 API만 씁니다.

## 필터

| 이름 | 하는 일 |
|---|---|
| 틸앤오렌지 | 파랑·초록은 청록으로, 피부·주황은 오렌지로 벌림. 그림자 청록, 하이라이트 주황. S커브 대비 |
| 라이카 클래식 | 깊은 중간톤 대비, 살짝 들린 블랙, 하이라이트 롤오프, 채도는 낮추고 빨강은 살림, 약한 비네팅·그레인 |
| 라이카 모노 | 붉은 필터 느낌의 채널 믹스 흑백, 강한 대비, 비네팅·그레인 |

- 강도 슬라이더(0–100%), 사진을 누르고 있으면 원본 비교
- 저장은 원본 해상도(긴 변 최대 4096px)로 `Pictures/JNK Filter` 에 JPEG
- 갤러리에서 공유 → JNK 필터 로 바로 열기
- 필터 수식은 `app/src/main/java/kr/co/jnkcorp/filter/Looks.kt` 한 파일

## 빌드

Android SDK 가 필요합니다 (`local.properties` 에 `sdk.dir=...` 또는 `ANDROID_HOME`).

```
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

minSdk 29 (안드로이드 10+). 지금은 디버그 키로 서명되어 있어 바로 설치는 되지만,
플레이스토어에 올리려면 서명 키를 따로 만들어야 합니다.
