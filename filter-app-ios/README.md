# FOFilter (아이폰)

안드로이드판 FOFilter 의 아이폰 버전. SwiftUI + AVFoundation + Core Image (GPU), iOS 16 이상.
LUT·글꼴 파일은 안드로이드판(`filter-app-android/app/src/main/assets`)과 같은 파일을 그대로 씁니다.

## 할 수 있는 것

- **앱 카메라** (앱을 열면 바로): 카메라 영상에 지금 LUT·보정·비네팅·그레인·프레임·워터마크를 입혀서 보여 줌 → 보이는 그대로 찍힘
  - 렌즈 버튼: 폰에 있는 렌즈만큼 (.5 · 1 · 3 / .5 · 1 · 5 / .5 · 1, 렌즈 하나면 숨김). 가상 카메라를 써서 줌하면 렌즈가 알아서 바뀜
  - 두 손가락 줌, 탭 초점·노출, 노출 보정(±), 플래시, 전/후면, 폰을 가로로 들면 가로 사진
  - LUT·최근 설정을 카메라 화면 아래 줄에서 바로 바꿈, 프레임 메뉴
  - 사진은 원본 해상도로 처리해 사진 앱에 저장. 썸네일을 누르면 방금 찍은 원본을 편집 화면에서 다시 손봄
- **영상**: "영상" 으로 바꾸고 빨간 버튼 → 필터를 입힌 화면 그대로 녹화(소리 포함) → 사진 앱에 저장
- **열기**: 사진 앱에서 사진·영상을 골라 편집 (영상은 저장할 때 영상 전체에 적용, HDR 영상은 SDR 로)
- **편집 화면**
  - 필터: 기본(원본 · 틸앤오렌지) · 라이카(틸 · 이터널 · 컨템퍼러리 CNT · 클래식 CLS) · 리코(시네마 옐로우) · 내 LUT, 강도, 최근 3개
  - 내 LUT: `.cube` 가져오기, 길게 눌러 내보내기·삭제, "이 설정을 LUT로 저장"
  - 보정: 노출 · 대비 · 하이라이트 · 그림자 · 채도 · 색온도 · 틴트 · 페이드, **색온도 자동 · 자동 노출** (슬라이더 이름을 누르면 기본값)
  - 효과: 그레인 · 비네팅
  - 프레임: 원본 · 시네마스코프(2.39:1) · 16:9 · 4:3 · 3:2 · 1:1, 방향 바꾸기, 사진을 끌어서 잘리는 위치 이동
  - 글자(워터마크): Pretendard · Playfair 이탤릭 · Great Vibes · 나눔손글씨 펜, 끌기 · 두 손가락 크기·회전, 가운데와 0°/90° 에 자석처럼 붙음(진동 + 안내선)
  - "원본" 버튼을 누르고 있으면 원본, 두 손가락으로 확대 → 손을 떼면 원래 크기
- 마지막 설정은 앱을 껐다 켜도 그대로, 저장할 때 쓴 설정은 "최근" 3개까지

안드로이드판에 있고 여기엔 아직 없는 것: 마스크 레이어(브러시), 참고 사진 색감 따오기, 기본(삼성) 카메라 연동
(아이폰은 앱 카메라도 시스템 사진 처리(Deep Fusion·스마트 HDR)를 거친 사진을 받습니다).

## 코드

| 파일 | 내용 |
|---|---|
| `Engine/Lut3D.swift` | 3D LUT, 삼선형 보간, `.cube` 읽기/쓰기, Core Image 용 데이터 |
| `Engine/Looks.swift` | 틸앤오렌지 · 시네마 옐로우 색 함수, 라이카 LUT 목록 |
| `Engine/LutMaker.swift` | 보정 슬라이더 → LUT, 자동 색온도 · 자동 노출 |
| `Engine/Settings.swift` | 설정 · 최근 · 워터마크 저장, 프레임 자르기, 렌즈 버튼 배율 |
| `Engine/LutLibrary.swift` | 기본·라이카·내 LUT, 저장/가져오기/내보내기 |
| `Render/Renderer.swift` | Core Image 파이프라인 (자르기 → LUT → 비네팅·그레인 → 워터마크), Metal 미리보기 |
| `Camera/CameraController.swift` | AVFoundation 카메라, 사진, 필터 입힌 영상 녹화(AVAssetWriter) |
| `Editor/*` | 편집 화면, 영상 내보내기(AVVideoComposition + Core Image) |

`Engine` 폴더는 Foundation 만 써서 리눅스의 Swift 로도 테스트할 수 있습니다.

## 빌드

맥이 있으면:

```
brew install xcodegen
cd filter-app-ios && xcodegen generate
open FOFilter.xcodeproj   # Signing 에서 내 Apple ID 팀을 고르고 아이폰에 실행
```

맥이 없으면 GitHub Actions(`.github/workflows/ios.yml`)가 macOS 에서 테스트하고 **서명 안 한 .ipa** 를 만들어
Actions 실행 결과의 Artifacts(`FOFilter-ios-unsigned`)에 올립니다.

## 아이폰에 설치하기

아이폰 앱은 애플 서명이 있어야 설치됩니다.

1. **Sideloadly / AltStore** (무료 Apple ID): PC나 맥에서 `FOFilter-unsigned.ipa` 를 내 Apple ID 로 서명해 설치.
   무료 계정은 7일마다 다시 서명해야 합니다. 아이폰 설정 > 개인정보 보호 및 보안 > 개발자 모드 켜기 필요.
2. **맥 + Xcode**: 위 "빌드" 대로 열어서 바로 실행 (무료 Apple ID 도 7일).
3. **TestFlight / 앱스토어**: Apple Developer Program(연 129,000원) 가입 후 서명 인증서를 Actions 비밀값에 넣으면
   빌드할 때마다 TestFlight 로 올리게 바꿀 수 있습니다.
