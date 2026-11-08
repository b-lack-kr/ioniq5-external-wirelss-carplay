# IONIQ 5 무선 CarPlay (외장 USB 동글)

**CarPlay 1.0.2 · by blackr**

한국형 아이오닉5 Gen5W에서 호환되는 5GHz USB Wi-Fi 동글로 무선 CarPlay를 연결합니다. 대상은 `NEEV.KOR.S5W_M.V039.001.260902` / `tcc803x`이며, NEMESIS와 **HMG-API 1.0.1 이상**, 활성화된 Hooks가 필요합니다. HMG-API 1.0.2를 사용 중이면 그대로 유지하세요. 기준 차량은 2023MY `NE1AE.S5BMC.KR`, 기준 동글은 Archer T3U Nano입니다.

음소거 1.1.0이 함께 설치되던 1.0.1과 달리, **1.0.2에서는 음소거 일시정지·자동 재생을 별도로 설치**합니다. 음소거 패키지는 CarPlay의 필수 조건이 아닙니다.

## 다운로드

| Windows | macOS |
| --- | --- |
| [Prepare-USB-Windows-v1.0.2.zip](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.2/Prepare-USB-Windows-v1.0.2.zip) | [Prepare-USB-macOS-v1.0.2.zip](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.2/Prepare-USB-macOS-v1.0.2.zip) |

GitHub [1.0.2 릴리스](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/tag/v1.0.2)의 **Assets**에서 사용 중인 PC에 맞는 위 파일 하나를 다운로드하세요. 압축을 푼 뒤 준비 도구를 실행해야 하며, 준비 도구 ZIP이나 소스 ZIP을 USB에 그대로 복사하는 방식이 아닙니다.

[설치 파일·준비 도구·소스 통합 ZIP](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.2/NEEV_260902_CarPlay_1.0.2_by_blackr.zip) · [릴리스와 체크섬](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/tag/v1.0.2) · [음소거·자동 재생 1.1.1 단독판](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause/releases/tag/v1.1.1)

준비 도구는 서명된 CarPlay 설치 파일과 GRT47 v2.0.2 원본을 각각 공식 릴리스에서 받아 해시를 확인합니다. PC에서 준비할 때 인터넷이 필요합니다. 이 저장소의 ZIP에는 타사 Wi-Fi 드라이버를 재배포하지 않습니다.

## USB 준비와 차량 설치

**신규 설치·업데이트 모두 설치 전에 기존 연결 기록을 먼저 지워 주세요.**

1. 차량 **설정 → 기기 연결**에서 등록된 Bluetooth 연결 기록을 모두 삭제합니다.
2. 아이폰 **설정 → Bluetooth → 기존 차량 이름 옆 ⓘ → 이 기기 지우기**를 누릅니다.
3. 아이폰 **설정 → 일반 → CarPlay → 기존 차량 → 이 자동차 지우기**를 누릅니다.

**1.0.1은 패키지를 삭제하지 않고 업데이트**할 수 있습니다. 이미 설치된 음소거 패키지의 상태도 유지합니다. **1.0.0 사용자는 CarPlay 패키지만** NEMESIS에서 **Uninstall**하고 남아 있는 **Apply changes**를 적용한 뒤 아래 설치를 진행하세요. NEMESIS와 HMG-API 자체는 삭제하지 않습니다. 처음 설치하는 사용자는 아래 순서대로 진행하면 됩니다.

1. USB 자료를 백업하고 준비 도구 ZIP을 PC 내부 저장 공간에 풉니다.
2. Windows는 `Prepare-USB-Windows.cmd`를 관리자 권한으로, Mac은 `USB-준비-macOS.command`를 실행합니다.
3. USB 이름·용량을 확인하고 대상 볼륨을 선택한 뒤 확인 문구를 입력합니다. **기존 파일·폴더를 삭제하고 설치 파일만 복사합니다.** 파티션 생성·포맷은 하지 않습니다. 준비와 파일 검증이 끝나면 안전하게 추출합니다.
4. 주차 상태에서 내비가 부팅된 뒤 USB를 데이터 포트에 꽂고 NEMESIS의 **Yes**를 누릅니다. **Operation completed successfully**까지 기다립니다.
5. 설치 USB를 빼고 5GHz 동글을 꽂은 상태에서 내비를 재부팅합니다.
6. 아이폰과 차량을 **Bluetooth로 새로 등록**합니다. 이어 표시되는 **CarPlay에 연결하시겠습니까?** 안내에서 연결을 허용합니다.

신규 설치 시 차량별 Wi-Fi 이름과 암호를 생성합니다. 위 순서대로 새 Bluetooth 등록에서 CarPlay 연결을 허용해야 합니다. 차량 Wi-Fi에 수동 접속하는 것만으로 CarPlay가 등록되지는 않습니다.

FAT32 또는 exFAT USB를 사용하세요. 여러 볼륨이 있으면 선택한 볼륨만 정리하며 운영체제 시스템 폴더 일부는 남습니다. [준비 도구 사용안내](usb-prep/source/사용안내.txt)를 참고하세요.

## 음소거·자동 재생 별도 설치

원하는 경우 [음소거 1.1.1 릴리스](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause/releases/tag/v1.1.1)의 **Assets**에서 **`IONIQ5_CarPlay_Mute_Pause_1.1.1_USB.zip`**을 받으세요. USB의 CarPlay 설치 파일과 로그를 백업하고 기존 설치 파일을 정리한 뒤, 압축 안의 `NEMESIS.data`와 `packages`를 USB 최상위에 복사합니다. `packages` 안의 ZIP은 풀지 않습니다. 차량에 꽂아 **Yes → Operation completed successfully**를 확인하고 내비를 재부팅합니다.

기존 음소거 1.0.0·1.1.0은 삭제하지 않고 1.1.1로 업데이트하세요. 1.1.1은 HMG-API 1.0.1 이상을 허용하며 CarPlay 본체를 다시 설치할 필요가 없습니다. 이 기능은 **CarPlay 전용**으로 Android Auto는 지원하지 않습니다. [음소거 설치·제거 안내](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause#설치업데이트제거)를 참고하세요.

## 포함 기능

- 5GHz 외부 USB Wi-Fi 동글과 무선 CarPlay. 정상 부팅·연결 대기 팝업은 표시하지 않습니다.
- 음악과 내비 안내 음성의 차량 음량 제어.
- CarPlay 회전 안내·남은 거리·입력 기반 차선 안내를 계기판/HUD에 전달.
- 일반 회전 AR 전환 기준은 CarPlay/Android Auto 공통 300m. AR 표시 기본값은 OFF입니다.
- 초기 거리 미수신을 0m로 표시하던 경로 개선, 차선 교차로 거리와 다음 회전 거리의 분리.
- 차량 차속을 CarPlay 위치 입력의 속도 필드에 반영. 좌표·시각·방향은 유지합니다.
- 굿바이 시작에 동글 AP를 종료하고 웰컴 시작에 재연결 준비. USB 단자의 물리 5V를 차단하는 기능은 아닙니다.
- 제한된 연결·전원·영상·TBT 진단을 저장. **같은 공개 1.0.2 설치 USB를 다시 꽂고 Yes를 누르면 재설치 대신 로그를 수집**합니다.

HMG-API 설정은 **AR 턴 표시**, **외부 USB Wi-Fi 동글 사용** 두 가지이며 변경 후 내비 재부팅이 필요합니다.

## 확인 범위

기준 차량에서 무선 CarPlay, 내비 안내 음량 조절 및 굿바이/문 열림 시 동글 종료를 사용자가 확인했습니다.

추천 차선의 독립 거리 입력, 모든 정션 그림의 도로 일치, 앱에 표시되는 속도와 계기판의 일치, 시동을 다시 켠 뒤 모든 상황에서의 자동 연결은 보장하지 않습니다. 화면 멈춤은 진단·조건부 복구 코드가 있으나 해결 완료로 보지 않습니다. 순정 카메라·제한속도 경고는 유지되며 CarPlay 경로와 다른 도로의 경고가 표시될 수 있습니다. 차선 수만으로 잘못된 범용 정션 그림을 고르던 경로는 껐습니다.

[변경 이력](CHANGELOG.md) · [소스·빌드 안내](BUILDING.md) · [제3자 고지](source/docs/THIRD-PARTY-NOTICES.txt)
