# IONIQ 5 USB 무선 CarPlay

**1.0.0 · by blackr**

한국형 아이오닉 5 Gen5W에서 호환되는 5 GHz USB Wi-Fi 동글로 무선 CarPlay를 연결합니다. 기준 차량은 2023년식 NE1AE.S5BMC.KR이며 대상 소프트웨어는 `NEEV.KOR.S5W_M.V039.001.260902`입니다. NEMESIS와 HMG-API 1.0.1, 활성화된 Hooks가 필요합니다.

## 다운로드

PC 운영체제에 맞는 준비 도구 하나를 받으세요. 실행하면 서명된 CarPlay 1.0.0과 GRT47 v2.0.2를 공식 릴리스에서 내려받아 SHA-256을 검사합니다. USB를 만들 때는 PC에 인터넷 연결이 필요합니다.

| Windows | macOS |
| --- | --- |
| [Windows용 준비 도구](https://github.com/ssyeonn/ioniq5-external-wirelss-carplay/releases/download/v1.0.0/Prepare-USB-Windows-v1.0.0.zip) | [Mac용 준비 도구](https://github.com/ssyeonn/ioniq5-external-wirelss-carplay/releases/download/v1.0.0/Prepare-USB-macOS-v1.0.0.zip) |

## USB 준비와 차량 설치

1. **포맷할 USB의 자료를 먼저 백업하세요.** 준비 도구 ZIP은 PC의 내부 저장 공간에 풀고, 차량에서 사용할 USB 메모리를 연결합니다.
2. Windows에서는 `Prepare-USB-Windows.cmd`를 관리자 권한으로 실행합니다. Mac에서는 `USB-준비-macOS.command`를 실행합니다.
3. 표시된 목록에서 USB를 선택하고 이름·용량·디스크 번호 또는 드라이브 문자를 확인합니다. 화면에 나온 확인 문구를 입력하면 **선택한 USB의 기존 데이터가 지워집니다.** 준비 도구가 포맷, 복사, 31개 설치 파일 검증을 끝내면 USB를 안전하게 추출합니다.
4. 주차 상태에서 내비가 부팅된 뒤 USB를 데이터 포트에 꽂고 NEMESIS 팝업에서 **Yes**를 누릅니다. CarPlay와 음소거 일시정지가 함께 설치됩니다. 최종 **Operation completed successfully**까지 기다립니다.
5. USB 메모리 대신 5 GHz 동글을 꽂고 내비를 재부팅합니다. 아이폰에서 차량 폰 프로젝션을 연결하고, 처음 등록하는 경우 CarPlay 사용을 승인합니다.

포맷하지 않고 파일을 준비하는 방법, 이전 버전 수동 삭제 및 복구 절차는 안내서에 있습니다. [사용안내](usb-prep/source/사용안내.txt)

## 포함 기능

- 무선 CarPlay, 5 GHz USB 동글용 AP, 연결 상태 기록. 정상 부팅과 연결 대기 중에는 팝업을 띄우지 않습니다.
- CarPlay 지도 앱이 제공하는 회전·거리·차선 안내를 차량에 전달합니다. 제공되는 데이터와 화면 표시는 앱, 경로, 계기판 및 HUD 구성에 따라 달라질 수 있습니다.
- CarPlay 음악 재생 중 운전대 음소거를 누르면 일시정지를 요청합니다. 단독 설치판과 같은 서명된 모드를 사용합니다. [음소거 단독판](https://github.com/ssyeonn/ioniq5-carplay-mute-pause/releases/tag/v1.0.0)

대상 펌웨어에서의 최종 실차 동작은 확인 중입니다. 저장소에는 Windows 및 macOS 준비 도구의 소스와 배포용 차량 패키지의 대응 소스가 들어 있습니다. [빌드 방법](source/BUILD-SOURCE-KO.txt) · [제3자 고지](source/docs/THIRD-PARTY-NOTICES.txt) · [업스트림 Wi-Fi 프로젝트](https://github.com/GRT47/HKG_5W_USB_Wlan/releases/tag/v2.0.2)


## 준비 도구 패키징

차량용 모드 빌드는 위의 빌드 방법을 참고합니다. 공개된 1.0.0과 동일한 PC용 준비 도구 ZIP을 만들려면, 이 릴리스의 `NEEV_260902_CarPlay_1.0.0_USB.zip`과 `NEEV_260902_CarPlay_1.0.0_SOURCE.zip`을 한 디렉터리에 두고 저장소 루트에서 다음 명령을 실행합니다. 준비 도구가 사용하는 릴리스 주소는 패키징 시 확정됩니다.

```sh
python3 usb-prep/package_release.py --signed-dir /path/to/signed-inputs --assets-dir ./build
```
