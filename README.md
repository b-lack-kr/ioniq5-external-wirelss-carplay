# IONIQ 5 USB 무선 CarPlay

**CarPlay 1.0.1 · 음소거 일시정지/자동 재생 1.1.0 포함 · by blackr**

한국형 아이오닉5 Gen5W에서 호환되는5GHz USB Wi-Fi 동글로 무선 CarPlay를 연결합니다. 대상은 `NEEV.KOR.S5W_M.V039.001.260902` / tcc803x이며, NEMESIS와 HMG-API1.0.1, 활성화된 Hooks가 필요합니다. 기준 차량은2023MY NE1AE.S5BMC.KR입니다.

2026-11-05 사용자가 해당 차량·Archer T3U Nano에서 설치와 동작을 확인했습니다. 지도 앱이 제공하는 안내 항목과 차량 화면 구성에 따라 TBT·차선 표시는 달라질 수 있습니다.

## 다운로드

운영체제에 맞는 준비 도구를 받으세요. 서명된 CarPlay1.0.1 통합 설치 파일과 GRT47 v2.0.2 원본을 공식 릴리스에서 내려받아 해시를 확인합니다. PC에서 USB를 준비할 때 인터넷이 필요합니다.

| Windows | macOS |
| --- | --- |
| [Windows용 준비 도구](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.1/Prepare-USB-Windows-v1.0.1.zip) | [Mac용 준비 도구](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.1/Prepare-USB-macOS-v1.0.1.zip) |

[설치 파일·준비 도구·소스 통합 ZIP](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.1/NEEV_260902_CarPlay_1.0.1_by_blackr.zip) · [릴리스/체크섬](https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/tag/v1.0.1)

## USB 준비와 차량 설치

1. USB의 자료를 백업하고 준비 도구 ZIP을 PC 내부 저장 공간에 풉니다.
2. Windows는 `Prepare-USB-Windows.cmd`를 관리자 권한으로, Mac은 `USB-준비-macOS.command`를 실행합니다.
3. 이름·용량을 확인하고 USB 드라이브를 선택합니다. 확인 문구를 입력하면 **선택한 USB 볼륨의 기존 파일·폴더를 삭제하고 설치 파일을 복사합니다.** 파티션을 만들거나 포맷하지 않으며 파일시스템과 볼륨 이름도 유지합니다. 31개 파일 검증이 끝나면 안전하게 추출합니다.
4. 주차 상태에서 내비가 부팅된 뒤 USB를 데이터 포트에 꽂고 NEMESIS의 **Yes**를 누릅니다. **Operation completed successfully**까지 기다립니다.
5. USB를5GHz 동글로 바꾸고 내비를 재부팅한 후 아이폰에서 차량 CarPlay를 연결합니다.

**기존 CarPlay1.0.0 사용자는** CarPlay 패키지를 삭제하고 남은 `Apply changes`를 적용한 뒤 재부팅하여 설치하세요. NEMESIS와 HMG-API 자체는 유지합니다. 이미 CarPlay1.0.1이면 삭제할 필요 없습니다. 통합판은 음소거1.0.0을1.1.0으로 업데이트하며, 동일한1.1.0이 설치돼 있으면 유지합니다.

차량에서 읽을 수 있는 FAT32 또는 exFAT USB를 사용하세요. USB에 볼륨이 여러 개 있으면 선택한 볼륨만 정리합니다. 운영체제가 관리하는 일부 시스템 폴더는 남을 수 있습니다. 자세한 범위와 PC에 `USB_READY` 폴더만 만드는 방법, 삭제 후 신규 설치·복구·로그 수집은 [사용안내](usb-prep/source/사용안내.txt)에 있습니다.

2026-11-05 USB 준비 방식을 수정했습니다. **버전은 1.0.1 그대로이므로 이전에 받은 준비 도구 ZIP은 다시 다운로드하세요.** 차량용 CarPlay·음소거 설치 파일은 변경하지 않았습니다.

## 포함 기능과 변경점

- 검증된 USB Wi-Fi 드라이버를 Android 부팅 완료 전에 로드합니다. 정상 부팅·연결 대기 팝업은 표시하지 않습니다.
- Mac의 `._LICENSE` 등 보조 파일 때문에 설치 완료 확인이 실패하던 문제를 수정했습니다. 차량 내부의 서명된 설치 파일을 검증합니다.
- 무선 CarPlay, 회전·거리·차선 안내 전달과 연결 상태 기록을 제공합니다.
- **음소거하면 일시정지, 버튼이나 볼륨 조절로 실제 음소거를 해제하면 재생**합니다. 음소거 전에 이미 일시정지돼 있던 음악은 자동 재생하지 않습니다. [음소거1.1.0 단독판](https://github.com/b-lack-kr/ioniq5-carplay-mute-pause/releases/tag/v1.1.0)과 동일한 서명 파일입니다.

[변경 이력](CHANGELOG.md) · [빌드 방법](source/BUILD-SOURCE-KO.txt) · [제3자 고지](source/docs/THIRD-PARTY-NOTICES.txt) · [업스트림 Wi-Fi 프로젝트](https://github.com/GRT47/HKG_5W_USB_Wlan/releases/tag/v2.0.2)

## 준비 도구 패키징

릴리스의 `NEEV_260902_CarPlay_1.0.1_USB.zip`과 `NEEV_260902_CarPlay_1.0.1_SOURCE.zip`을 같은 디렉터리에 두고 실행합니다.

```sh
python3 usb-prep/package_release.py --signed-dir /path/to/signed-inputs --assets-dir ./build
```

차량용 코드를 다시 빌드하려면 위 빌드 방법을 따릅니다. 서명키와 출력 폴더는 저장소 밖에 두세요. 자신의 키로 빌드한 파일은 공식 설치와 서명·해시가 달라집니다.
