# IONIQ 5 무선 CarPlay (외장 USB 동글)

**CarPlay 1.0.3 · by blackr — 연결 시험용 릴리스**

한국형 아이오닉5 Gen5W에서 호환되는 5GHz USB Wi-Fi 동글로 무선 CarPlay를 연결합니다. 대상은 `NEEV.KOR.S5W_M.V039.001.260902` / `tcc803x`이며, NEMESIS와 **HMG-API 1.0.1 이상**, 활성화된 Hooks가 필요합니다. HMG-API 1.0.2를 사용 중이면 그대로 유지하세요. 기준 차량은 2023MY `NE1AE.S5BMC.KR`, 기준 동글은 Archer T3U Nano입니다.

음소거 1.1.0이 함께 설치되던 1.0.1과 달리, **1.0.2부터 음소거 일시정지·자동 재생을 별도로 설치**합니다. 이미 설치되어 있다면 유지됩니다. 기존 1.0.1 사용자는 음소거 패키지를 따로 삭제하지 않으면 그대로 사용할 수 있습니다.

## 다운로드

| Windows | macOS |
| --- | --- |
| [Prepare-USB-Windows-v1.0.3.zip](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.3/Prepare-USB-Windows-v1.0.3.zip) | [Prepare-USB-macOS-v1.0.3.zip](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.3/Prepare-USB-macOS-v1.0.3.zip) |

GitHub [1.0.3 릴리스](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/tag/v1.0.3)의 **Assets**에서 사용 중인 PC에 맞는 위 파일 하나를 다운로드하세요. 압축을 푼 뒤 준비 도구를 실행해야 하며, 준비 도구 ZIP이나 소스 ZIP을 USB에 그대로 복사하는 방식이 아닙니다.

[설치 파일·준비 도구·소스 통합 ZIP](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.3/NEEV_260902_CarPlay_1.0.3_by_blackr.zip) · [릴리스와 체크섬](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/tag/v1.0.3) · [음소거·자동 재생 1.1.1 단독판](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause/releases/tag/v1.1.1)

준비 도구는 서명된 CarPlay 설치 파일과 GRT47 v2.0.2 원본을 각각 공식 릴리스에서 받아 해시를 확인합니다. PC에서 준비할 때 인터넷이 필요합니다. 이 저장소의 ZIP에는 타사 Wi-Fi 드라이버를 재배포하지 않습니다.

**2026-11-09 설치 수정본:** 삭제 후 설치가 실패했다면 준비 도구 ZIP을 새로 받아 USB를 다시 만드세요. 실패 후 복구된 설치 기록도 처리합니다. CarPlay 본체는 동일하며, 설치 실행 방식을 수정한 이번 파일은 차량 재시험이 필요합니다.

## USB 준비와 차량 설치

**신규 설치·업데이트 모두 설치 전에 기존 연결 기록을 먼저 지워 주세요.**

1. 차량 **설정 → 기기 연결**에서 등록된 Bluetooth 연결 기록을 모두 삭제합니다.
2. 아이폰 **설정 → Bluetooth → 기존 차량 이름 옆 ⓘ → 이 기기 지우기**를 누릅니다.
3. 아이폰 **설정 → 일반 → CarPlay → 기존 차량 → 이 자동차 지우기**를 누릅니다.

**기존 CarPlay 패키지가 있으면 NEMESIS → My Packages에서 Uninstall한 후 설치하세요.** 남아 있는 **Apply changes**도 적용합니다. NEMESIS와 HMG-API 자체는 유지합니다. 처음 설치하는 사용자는 아래 순서대로 진행하면 됩니다.

**1.0.2 삭제 후 1.0.3 설치, 1.0.3 삭제 후 재설치를 지원합니다.** 이미 1.0.3이 설치된 상태에서 같은 USB를 다시 연결하면 재설치 대신 로그를 수집합니다.

1. USB 자료를 백업하고 준비 도구 ZIP을 PC 내부 저장 공간에 풉니다.
2. Windows는 `Prepare-USB-Windows.cmd`를 관리자 권한으로, Mac은 `USB-준비-macOS.command`를 실행합니다.
3. USB 이름·용량을 확인하고 대상 볼륨을 선택한 뒤 확인 문구를 입력합니다. **기존 파일·폴더를 삭제하고 설치 파일만 복사합니다.** 파티션 생성·포맷은 하지 않습니다. 준비와 파일 검증이 끝나면 안전하게 추출합니다.
4. 주차 상태에서 내비가 부팅된 뒤 USB를 데이터 포트에 꽂고 NEMESIS의 **Yes**를 누릅니다. **Operation completed successfully**까지 기다립니다.
5. 설치 USB를 빼고 5GHz 동글을 꽂은 상태에서 내비를 재부팅합니다.
6. 아이폰과 차량을 **Bluetooth로 새로 등록**합니다. 이어 표시되는 **CarPlay에 연결하시겠습니까?** 안내에서 연결을 허용합니다.

연결 안내가 나오지 않으면 **차량 설정 → 기기 연결 → 해당 아이폰의 CarPlay 버튼**으로 다시 시도할 수 있습니다. 버튼은 연결을 요청하며 아이폰의 허용 절차가 필요합니다.

신규 설치 시 차량별 Wi-Fi 이름과 암호를 생성합니다. 위 순서대로 새 Bluetooth 등록에서 CarPlay 연결을 허용해야 합니다. 차량 Wi-Fi에 수동 접속하는 것만으로 CarPlay가 등록되지는 않습니다.

**Wi-Fi는 켜지는데 CarPlay 연결 안내가 나오지 않는 경우:** 설치 완료 메시지와 별개로 HMG-API의 Hooks가 동작해야 합니다. 같은 설치 USB를 차량에 다시 꽂고 Yes를 눌러 기록을 수집하세요. 결과 폴더의 `HMG_HOOK_STATUS.txt`에 실제 후크 로딩 상태가 저장됩니다. Bluetooth 재등록만 반복하기 전에 이 기록을 확인하세요.

FAT32 또는 exFAT USB를 사용하세요. 여러 볼륨이 있으면 선택한 볼륨만 정리하며 운영체제 시스템 폴더 일부는 남습니다. [준비 도구 사용안내](usb-prep/source/사용안내.txt)를 참고하세요.

## 음소거·자동 재생 별도 설치

원하는 경우 [음소거 1.1.1 릴리스](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause/releases/tag/v1.1.1)의 **Assets**에서 **`IONIQ5_CarPlay_Mute_Pause_1.1.1_USB.zip`**을 받으세요. USB의 CarPlay 설치 파일과 로그를 백업하고 기존 설치 파일을 정리한 뒤, 압축 안의 `NEMESIS.data`와 `packages`를 USB 최상위에 복사합니다. `packages` 안의 ZIP은 풀지 않습니다. 차량에 꽂아 **Yes → Operation completed successfully**를 확인하고 내비를 재부팅합니다. **CarPlay 패키지 설치 후 바로 재부팅하지 않고, 음소거 패키지까지 설치한 후 동글을 꽂고 한 번에 재부팅해도 됩니다.**

기존 음소거 1.0.0·1.1.0은 삭제하지 않고 1.1.1로 업데이트하세요. 1.1.1은 HMG-API 1.0.1 이상을 허용하며 CarPlay 본체를 다시 설치할 필요가 없습니다. 이 기능은 **CarPlay 전용**으로 Android Auto는 지원하지 않습니다. [음소거 설치·제거 안내](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause#설치업데이트제거)를 참고하세요.

## 1.0.3 변경점

- 기기 연결 목록에 아이폰별 CarPlay 연결 버튼을 추가했습니다.
- 실제 연결 상태를 버튼에 반영하고, 연결 서비스가 준비되는 동안 기다리도록 했습니다.
- 완료되지 않은 연결을 버튼으로 다시 시도할 수 있습니다.
- 멈춘 동글 서비스를 버튼으로 다시 시작합니다. 실행 중인 서비스와 Wi-Fi 이름·암호는 유지합니다.
- 부가 기능의 초기화 오류가 CarPlay 연결 초기화를 막지 않도록 수정했습니다.
- 설치와 로그 수집에 실제 HMG 후크 로딩 상태 및 연결 단계 기록을 추가했습니다.

## 1.0.2 변경점

- 차량에서 음악과 별도로 내비 안내 음량을 조절하도록 수정했습니다.
- 처음 안내할 때 아직 받지 못한 거리를 0m로 표시하던 처리를 개선했습니다.
- 다음 회전까지의 거리와 추천 차선이 가리키는 교차로까지의 거리를 분리했습니다.
- AR뷰 기능이 추가됩니다. 기본 OFF이며 HMG-API 설정에서 켤 수 있습니다.
- 시동 종료 후 차량 문을 열면 동글의 무선 AP를 종료하며, CarPlay 연결이 종료됩니다.
- CarPlay 설치와 음소거 패키지 설치를 분리했습니다.

HMG-API 설정은 **AR 턴 표시**, **외부 USB Wi-Fi 동글 사용** 두 가지이며 변경 후 내비 재부팅이 필요합니다.

## 확인 범위

**1.0.3은 차량 연결 시험을 위한 릴리스입니다.** PC에서 설치·삭제 후 재설치·로그 수집 경로와 Mac USB 준비를 확인했으며, 기준 차량에서 HMG 후크 실행을 복구하고 재부팅한 뒤 연결이 동작한다는 사용자 확인을 받았습니다. GitHub 다운로드 파일로 삭제 후 재설치하는 최종 확인과 새 연결 버튼의 전체 동작 검증은 남아 있습니다.

기존 버전에서 무선 CarPlay, 내비 안내 음량 조절 및 굿바이/문 열림 시 동글 종료를 사용자가 확인했습니다.

추천 차선의 독립 거리 입력, 모든 정션 그림의 도로 일치, 앱에 표시되는 속도와 계기판의 일치, 시동을 다시 켠 뒤 모든 상황에서의 자동 연결은 보장하지 않습니다. 화면 멈춤은 진단·조건부 복구 코드가 있으나 해결 완료로 보지 않습니다. 순정 카메라·제한속도 경고는 유지되며 CarPlay 경로와 다른 도로의 경고가 표시될 수 있습니다. 차선 수만으로 잘못된 범용 정션 그림을 고르던 경로는 껐습니다.

[변경 이력](CHANGELOG.md) · [소스·빌드 안내](BUILDING.md) · [제3자 고지](source/docs/THIRD-PARTY-NOTICES.txt)
