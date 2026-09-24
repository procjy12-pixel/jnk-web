# FOFilter (안드로이드)

사진을 찍거나 불러와 LUT 를 입히고, LUT 를 직접 만드는 앱. 외부 라이브러리 없이 Kotlin + 안드로이드 기본 API만 씁니다.

## 할 수 있는 것

- **촬영**: 기본 카메라 앱으로 찍어서 바로 편집 (원본은 `Pictures/FOFilter/원본`)
- **프레임**: 원본 · 시네마스코프(2.39:1) · 16:9 · 4:3 · 3:2 · 1:1, 가로/세로 전환
- **미리보기**: 누르고 있으면 원본, 두 손가락으로 벌려 확대 → 손을 떼면 원래 크기로

**LUT 적용** 탭
- 분류: 기본 · 컬러 네거 · 슬라이드 · 후지 시뮬 · 시네마 · 흑백 · 인스턴트 · 내 LUT
- 기본 룩 3종(틸앤오렌지 · 라이카 클래식 · 라이카 모노) + 필름 에뮬레이션 29종 + 가져온 `.cube`
- 강도, 보정(노출 · 대비 · 하이라이트 · 그림자 · 채도 · 색온도), 필름 효과(그레인 · 비네팅)
- 슬라이더 이름을 누르면 기본값으로. "이 설정을 LUT로" 로 지금 조합을 LUT 로 저장
- LUT 를 길게 누르면: `.cube` 로 내보내기(다운로드/FOFilter) · 이 LUT 를 기준으로 새로 만들기 · 삭제

**LUT 만들기** 탭
- 기준 LUT 를 고르고 그 위에 노출 · 대비 · 하이라이트 · 그림자 · 채도 · 색온도 · 틴트 · 페이드 · 스플릿 토닝(그림자/밝은 부분 색과 양)
- **참고 사진 색감 따오기**: 참고 사진의 색 분포를 지금 사진으로 옮기는 LUT (Lab 공간 Reinhard 색 전이, 밝기는 약하게·색은 강하게)
- 이름을 붙여 저장하면 33³ LUT 로 구워져 목록에 추가됩니다

사진 저장은 원본 해상도(긴 변 최대 4096px)를 프레임대로 잘라 `Pictures/FOFilter` 에 JPEG.

그레인·비네팅은 위치마다 다른 효과라 LUT 에 담기지 않습니다. 내보낸 `.cube` 에는 색 변환만 들어갑니다.

## 코드

| 파일 | 내용 |
|---|---|
| `Lut3D.kt` | 3D LUT, 삼선형 보간, `.cube` 읽기/쓰기 |
| `Looks.kt` | 기본 룩 색 함수 (LUT 로 구워서 씀) |
| `LutMaker.kt` | 슬라이더 → LUT, 참고 사진 색 전이 |
| `Pipeline.kt` | 픽셀 배열에 LUT·강도·그레인·비네팅 적용 (멀티스레드) |
| `Presets.kt` | 같이 넣은 필름 LUT 목록 (assets/luts) |
| `LutLibrary.kt` | 기본·필름·내 LUT 불러오기, 저장/가져오기/내보내기 |
| `MainActivity.kt` | 화면 |

`Lut3D`·`Looks`·`LutMaker`·`Pipeline` 은 안드로이드 클래스에 기대지 않아 JVM 유닛 테스트로 돌립니다.

## 빌드 · 테스트

Android SDK 가 필요합니다 (`local.properties` 에 `sdk.dir=...` 또는 `ANDROID_HOME`).

```
./gradlew testReleaseUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

실제 앱 코드로 샘플 사진 비교표를 뽑으려면 (입력·출력은 P6 PPM):

```
PREVIEW_OUT=/tmp/out PREVIEW_SRC=photo.ppm PREVIEW_REF=ref.ppm ./gradlew testReleaseUnitTest --rerun-tasks
```

minSdk 29 (안드로이드 10+). 지금은 디버그 키로 서명되어 있어 바로 설치는 되지만,
플레이스토어에 올리려면 서명 키를 따로 만들어야 합니다.

## 같이 넣은 LUT 의 출처와 라이선스

`app/src/main/assets/luts` 의 필름 에뮬레이션 29종은
[G'MIC Film LUTs Collection](https://github.com/YahiaAngelo/Film-Luts) (MIT, 저작권 고지는
`assets/luts/LICENSE-Film-Luts.txt`) 에서 골라 숫자 자릿수만 줄였습니다.
원래는 G'MIC · RawTherapee 필름 시뮬레이션 계열이라, 스토어에 올리기 전에 원 출처 라이선스(CC BY-SA)도
한 번 확인하는 게 안전합니다. 필름 이름(Portra, Velvia 등)은 각 제조사 상표이고, 이 LUT 들은 그 필름을
흉내 낸 것일 뿐 제조사와 관계가 없습니다.
