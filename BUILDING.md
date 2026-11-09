# 소스와 빌드


[변경 이력](CHANGELOG.md) · [빌드 방법](source/BUILD-SOURCE-KO.txt) · [제3자 고지](source/docs/THIRD-PARTY-NOTICES.txt) · [업스트림 Wi-Fi 프로젝트](https://github.com/GRT47/HKG_5W_USB_Wlan/releases/tag/v2.0.2)

릴리스의 `NEEV_260902_CarPlay_1.0.3_USB.zip`과 `NEEV_260902_CarPlay_1.0.3_SOURCE.zip`을 같은 디렉터리에 두고 준비 도구를 패키징합니다.

```sh
python3 usb-prep/package_release.py --signed-dir /path/to/signed-inputs --assets-dir ./build
```

대응 소스, 빌드·설치 스크립트와 라이선스를 함께 제공합니다. 개인 로그·순정 펌웨어·지도 이미지는 포함하지 않습니다. 서명키와 출력 폴더는 저장소 밖에 두세요.
