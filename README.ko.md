# Atlas SDK for Android

[English](README.md) · [中文](README.zh.md)

[App Atlas](https://appatlas.dev)의 Android 클라이언트. 순수 Java이며
**minSdk 16**부터 지원합니다. 기존 SDK들이 버린 하한입니다.

## 설치

<!-- tabs:start -->
#### build.gradle

```gradle
dependencies {
    implementation 'dev.appatlas:atlas-links:0.1.1'
}
```

#### build.gradle.kts

```kotlin
dependencies {
    implementation("dev.appatlas:atlas-links:0.1.1")
}
```

#### libs.versions.toml

```toml
[libraries]
atlas-links = { module = "dev.appatlas:atlas-links", version = "0.1.1" }
```

#### pom.xml

```xml
<dependency>
  <groupId>dev.appatlas</groupId>
  <artifactId>atlas-links</artifactId>
  <version>0.1.1</version>
  <type>aar</type>
</dependency>
```
<!-- tabs:end -->

<!-- guide:start -->
## 시작

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 매니페스트 <application>의 android:name으로 등록된 클래스.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // 모듈(Links, 이후 Push·Crash)은 이 다음 줄부터 배선합니다.
    }
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: 매니페스트 <application>의 android:name으로 등록된 클래스.
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");
        // 모듈(Links, 이후 Push·Crash)은 이 다음 줄부터 배선합니다.
    }
}
```
<!-- tabs:end -->

## Links

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 매니페스트 <application>의 android:name으로 등록된 클래스.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")

        AtlasLinks.setListener { link ->
            // 직접 열림과 디퍼드 링크가 같은 자리로 옵니다.
            // link.deferred: 설치를 건너온 링크면 true.
            // link.match: referrer / clipboard / campaign_id / relink.
            // link.path와 link.payload로 화면을 이동합니다. 예:
            // link.path?.let { openScreen(it, link.payload) }
        }
    }
}
```

```kotlin title="MainActivity.kt"
// MainActivity.kt: 런처 액티비티. 오래된 인텐트 재전달은 내부에서 걸러냅니다.
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AtlasLinks.handle(intent)
}

// singleTop 액티비티는 다시 열릴 때 여기로 옵니다.
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    AtlasLinks.handle(intent)
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: 매니페스트 <application>의 android:name으로 등록된 클래스.
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");

        AtlasLinks.setListener(new AtlasLinkListener() {
            @Override
            public void onLink(AtlasLink link) {
                // 직접 열림과 디퍼드 링크가 같은 자리로 옵니다.
                // link.deferred: 설치를 건너온 링크면 true.
                // link.match: referrer / clipboard / campaign_id / relink.
                // link.path와 link.payload로 화면을 이동합니다. 예:
                // if (link.path != null) openScreen(link.path, link.payload);
            }
        });
    }
}
```

```java title="MainActivity.java"
// MainActivity.java: 런처 액티비티. 오래된 인텐트 재전달은 내부에서 걸러냅니다.
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AtlasLinks.handle(getIntent());
}

// singleTop 액티비티는 다시 열릴 때 여기로 옵니다.
@Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    AtlasLinks.handle(intent);
}
```
<!-- tabs:end -->

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

```xml title="AndroidManifest.xml"
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml title="res/values/strings.xml"
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
