# ascii-cam-android (아스키캠)

카메라 영상을 실시간으로 ASCII 아트로 바꿔 보여 주는 안드로이드 앱.
터미널용 [`ascii-render`](../ascii-render) 의 안드로이드판이다.

Preview 유스케이스를 아예 바인딩하지 않아서, 화면에 보이는 건 글자 격자뿐이다.
영상은 기기 밖으로 나가지 않고 네트워크 권한도 없다.

## 기능

- 전/후면 카메라 실시간 ASCII 변환
- 색 모드 3종
  - 카메라 색: 글자마다 원본 픽셀 색
  - 그라디언트: 밝은 두 색 그라디언트 (선셋 / 오로라 / 네온 / 라임 / 캔디)
  - 단색
- 글자 램프 5종: 클래식 `.:-=+*#%@` / 블록 / 촘촘 / 숫자 / 점
- 밀도 30~200칸, 대비 조절
- 자동 보정(프레임마다 밝기 범위를 0~255 로 펴기), 밝은 배경, 반전
- PNG 저장 (갤러리 `Pictures/AsciiCam`, 가로 2160px)
- ASCII 텍스트 클립보드 복사
- fps 표시

## 빌드

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

릴리스 APK 는 서명 설정이 없어 unsigned 로 나온다.

```bash
./gradlew assembleRelease
```

- 최소 SDK 29 (Android 10). MediaStore 로만 저장해서 저장소 권한이 아예 필요 없다.
- 권한은 `CAMERA` 하나뿐.

## 구조

| 파일 | 역할 |
|------|------|
| `AsciiCore.kt` | YUV 평면 → ASCII 격자 변환. 안드로이드 API 를 쓰지 않는 순수 로직이라 JVM 유닛 테스트로 검증한다 |
| `AsciiRenderer.kt` | 격자를 Canvas 에 그리는 코드. 화면과 PNG 저장이 같은 함수를 쓴다 |
| `AsciiView.kt` | 화면을 재서 격자 크기를 정하고 Compose Canvas 에 그린다 |
| `CameraSession.kt` | ImageAnalysis 바인딩 |
| `AsciiViewModel.kt` | 분석 스레드 ↔ Compose 상태 연결 |
| `Capture.kt` | MediaStore PNG 저장 |

### 알아 둘 것

**격자 크기는 화면이 정한다.** 고정폭 글자 한 칸은 세로가 가로의 약 2배라,
`cols` 만 정하고 글꼴 크기를 역산한 뒤 화면 높이를 나눠 `rows` 를 얻는다.
그 결과를 분석 스레드로 넘겨 다음 프레임부터 그 크기로 만든다.
설정을 바꾼 직후 한두 프레임은 격자가 안 맞아서 그리지 않고 넘긴다.

**비율은 센터 크롭으로 맞춘다.** 분석 프레임은 4:3 인데 화면은 더 길쭉해서,
늘리는 대신 회전된 프레임의 가운데만 잘라 쓴다.

**회전은 직접 처리한다.** `ImageProxy.imageInfo.rotationDegrees` 만큼
원본을 시계방향으로 돌린 좌표계에서 샘플링한다. 버퍼를 실제로 회전시키지 않는다.

**카메라 색은 두 겹으로 그린다.** 글자마다 `drawText` 를 부르면 수천 번이 되니까,
흰 글자를 한 겹(saveLayer)에 행 단위로 찍고 그 위에
`cols x rows` 짜리 색 비트맵을 `SRC_IN` 으로 덮는다. `drawText` 호출이 행 수만큼으로 줄어든다.

**어두운 곳의 색은 끌어올린다.** 밝기는 이미 글자가 표현하므로,
색은 최대 2.5배까지 밝기를 올려 채도를 살린다.

## 검증

유닛 테스트 14개로 변환 로직을 덮는다 (회전 4방향, 미러, 밝기 방향,
센터 크롭, 자동 보정, 대비, 반전, YUV→RGB).
카메라 미리보기와 실제 렌더링은 실기기에서 확인해야 한다.
