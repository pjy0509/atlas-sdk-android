# Atlas SDK for Android

[English](README.md) · [中文](README.zh.md)

[App Atlas](https://appatlas.dev)의 Android 클라이언트. 순수 Java이며
**minSdk 16**부터 지원합니다. 기존 SDK들이 버린 하한입니다.

```gradle
dependencies {
    implementation 'dev.appatlas:atlas-links:0.1.1'   // atlas-core를 함께 가져옵니다
}
```

<!-- guide:start -->
```java
// Application.onCreate
Atlas.start(this, "sdk_…");

AtlasLinks.setListener(new AtlasLinkListener() {
    @Override
    public void onLink(AtlasLink link) {
        // link.payload / link.path / link.deferred / link.match
        // link.channel / link.campaign / link.shortId
    }
});
```

```java
// 런처 액티비티에서 매 시작마다 호출합니다. 오래된 인텐트 재전달은 내부에서 걸러냅니다.
AtlasLinks.handle(getIntent());
```

디퍼드 링크는 따로 호출할 것이 없습니다. 첫 실행이 Play install referrer를
한 번 읽고 거기 실린 토큰을 교환하며, 같은 리스너가 링크를 받습니다.
`AtlasLinks.firstReferringLink()`는 설치를 만든 링크를 언제까지나 돌려주므로
추천 보상에 쓸 수 있습니다.

## 모듈

| 아티팩트 | 역할 | minSdk |
|---|---|---|
| `atlas-core` | 엔벨로프, 디스크 큐, 전송기. 모든 모듈의 바탕입니다. | 16 |
| `atlas-links` | 딥링크 유입: 디퍼드 교환과 직접 열림. | 16 |

`atlas-links`가 Play install-referrer 라이브러리를 함께 가져오므로,
위의 의존성 하나로 디퍼드 링크까지 동작합니다.

## 딥링크 감지 (선택)

방문 페이지의 `navigator.getInstalledRelatedApps()`가 추측 대신 "설치됨"으로
답하려면, 앱이 링크 출처를 보증해야 합니다. `AndroidManifest.xml`의
`<application>` 안에 추가합니다.

```xml
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml
<!-- res/values/strings.xml — asset_statements가 이미 있다면 아래 객체를
     기존 배열에 추가합니다. -->
<string name="asset_statements" translatable="false">
  [{
    \"relation\": [\"delegate_permission/common.handle_all_urls\"],
    \"target\": {\"namespace\": \"web\", \"site\": \"https://appatlas.dev\"}
  }]
</string>
```

## 프라이버시

SDK는 설치 단위의 난수 id 하나를 만들 뿐, 기기 식별자나 광고 식별자를 읽지
않습니다. GAID도, 하드웨어 id도, 삭제 후에 남는 어떤 것도 읽지 않습니다.
함께 보내는 기기 정보(OS 버전, 모델, 로캘, 시간대, 앱 버전, 설치 출처)는
일반적인 크래시 리포트 항목이며 누구도 특정하지 않습니다.
<!-- guide:end -->

## 검사

```sh
sh check-core.sh                             # JVM 반쪽들 + 골든 바이트 비교
ATLAS_SERVER=../app-atlas sh check-core.sh   # 서버의 실제 파서까지
```

MIT.
