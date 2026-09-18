# Atlas SDK for Android

[English](README.md) · [中文](README.zh.md)

[App Atlas](https://appatlas.dev)의 Android 클라이언트. 딥링크와 크래시 리포팅.
순수 Java이며 **minSdk 16**부터 지원합니다. 기존 SDK들이 버린 하한입니다.
네이티브 크래시 캡처는 NDK의 하한인 21에서 별도 아티팩트로 제공합니다.

## 설치

<!-- tabs:start -->
#### build.gradle

```gradle
dependencies {
    implementation 'dev.appatlas:atlas-links:0.2.0'
    implementation 'dev.appatlas:atlas-crash:0.2.0'
    implementation 'dev.appatlas:atlas-crash-ndk:0.2.0'   // C/C++ 코드가 있을 때
}
```

#### build.gradle.kts

```kotlin
dependencies {
    implementation("dev.appatlas:atlas-links:0.2.0")
    implementation("dev.appatlas:atlas-crash:0.2.0")
    implementation("dev.appatlas:atlas-crash-ndk:0.2.0")
}
```

#### libs.versions.toml

```toml
[libraries]
atlas-links = { module = "dev.appatlas:atlas-links", version = "0.2.0" }
atlas-crash = { module = "dev.appatlas:atlas-crash", version = "0.2.0" }
atlas-crash-ndk = { module = "dev.appatlas:atlas-crash-ndk", version = "0.2.0" }
```

#### pom.xml

```xml
<dependency>
  <groupId>dev.appatlas</groupId>
  <artifactId>atlas-links</artifactId>
  <version>0.2.0</version>
  <type>aar</type>
</dependency>
<dependency>
  <groupId>dev.appatlas</groupId>
  <artifactId>atlas-crash</artifactId>
  <version>0.2.0</version>
  <type>aar</type>
</dependency>
<dependency>
  <groupId>dev.appatlas</groupId>
  <artifactId>atlas-crash-ndk</artifactId>
  <version>0.2.0</version>
  <type>aar</type>
</dependency>
```
<!-- tabs:end -->

<!-- guide:start -->

## 코어

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 매니페스트 <application>의 android:name으로 등록된 클래스.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // 모듈(Links, Crash, 이후 Push)은 이 다음 줄부터 배선합니다.
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
        // 모듈(Links, Crash, 이후 Push)은 이 다음 줄부터 배선합니다.
    }
}
```
<!-- tabs:end -->

### 모듈

| 아티팩트 | 역할 | minSdk |
|---|---|---|
| `atlas-core` | 엔벨로프, 디스크 큐, 전송기. 모든 모듈의 바탕입니다. | 16 |
| `atlas-links` | 딥링크 유입: 디퍼드 교환과 직접 열림. | 16 |
| `atlas-crash` | 크래시 리포팅: 미처리 예외, 처리된 오류, ANR, kill, 세션. | 16 |
| `atlas-crash-ndk` | C/C++ 코드의 네이티브 시그널 캡처: SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL. | 21 |

`atlas-links`가 Play install-referrer 라이브러리를 함께 가져오므로,
위의 의존성 하나로 디퍼드 링크까지 동작합니다.

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

### 딥링크 감지 (선택)

방문 페이지의 `navigator.getInstalledRelatedApps()`가 추측 대신 "설치됨"으로
답하려면, 앱이 링크 출처를 보증해야 합니다. `AndroidManifest.xml`의
`<application>` 안에 추가합니다.

```xml title="AndroidManifest.xml"
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml title="res/values/strings.xml"
<!-- res/values/strings.xml. asset_statements가 이미 있다면 아래 객체를
     기존 배열에 추가합니다. -->
<string name="asset_statements" translatable="false">
  [{
    \"relation\": [\"delegate_permission/common.handle_all_urls\"],
    \"target\": {\"namespace\": \"web\", \"site\": \"https://appatlas.dev\"}
  }]
</string>
```

## Crash

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 매니페스트 <application>의 android:name으로 등록된 클래스.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // 이 줄부터 크래시, ANR, kill이 수집됩니다. 아래는 선택입니다.

        // 로그인한 사용자의 자체 id와, 크래시 옆에서 보고 싶은 상태.
        AtlasCrash.setUserId("u-123")
        AtlasCrash.setKey("screen", "checkout")
        AtlasCrash.leaveBreadcrumb("cart", "add")
        AtlasCrash.log("cart total recomputed")
    }
}
```

```kotlin title="CheckoutActivity.kt"
// CheckoutActivity.kt: 예외를 잡았지만 알아 둘 가치가 있는 모든 자리.
private fun pay() {
    try {
        check(intent.hasExtra("cart")) { "cart is missing" }
    } catch (error: IllegalStateException) {
        AtlasCrash.recordError(error)
        // 앱의 복구 처리는 여기에 둡니다. 예:
        // showRetry()
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
        // 이 줄부터 크래시, ANR, kill이 수집됩니다. 아래는 선택입니다.

        // 로그인한 사용자의 자체 id와, 크래시 옆에서 보고 싶은 상태.
        AtlasCrash.setUserId("u-123");
        AtlasCrash.setKey("screen", "checkout");
        AtlasCrash.leaveBreadcrumb("cart", "add");
        AtlasCrash.log("cart total recomputed");
    }
}
```

```java title="CheckoutActivity.java"
// CheckoutActivity.java: 예외를 잡았지만 알아 둘 가치가 있는 모든 자리.
private void pay() {
    try {
        if (!getIntent().hasExtra("cart")) {
            throw new IllegalStateException("cart is missing");
        }
    } catch (IllegalStateException error) {
        AtlasCrash.recordError(error);
        // 앱의 복구 처리는 여기에 둡니다. 예:
        // showRetry();
    }
}
```
<!-- tabs:end -->

`Atlas.start` 외에 아무 호출 없이 잡히는 것:

| 죽는 방식 | 잡는 방법 |
|---|---|
| 어느 스레드든 미처리 예외, `OutOfMemoryError` 포함. | 프로세스 전체 핸들러. 기존 핸들러 앞에 체인으로 들어갑니다. |
| ANR. | Android 11 이상: OS 자체의 종료 기록과 스레드 덤프. 그 아래: 메인 루퍼 워치독. 시스템의 오류 상태와 교차 확인하므로 디버거로 멈춘 것은 세지 않습니다. |
| 외부에서 온 kill, 저메모리 kill, 과다 자원 사용 kill. | 다음 실행 때 OS 종료 기록에서 읽습니다. |
| C/C++ 코드의 네이티브 시그널(SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL), 어느 스레드든. | `atlas-crash-ndk`. async-signal-safe 핸들러가 기록하고 다음 실행 때 보냅니다. |

크래시는 죽어 가는 스레드에서 디스크에 먼저 기록되고, 다음 실행 때 세션 종료 상태와 함께
전송됩니다. crash-free 세션은 이 세션으로 계산합니다. 모든 리포트에 최근 브레드크럼 100개,
키 64개, `AtlasCrash.log`의 최근 64KB, 그리고 그 순간의 기기 상태가 실립니다. 남은 메모리와
디스크, 포그라운드 여부, 화면에 있던 액티비티입니다. 시작 후 5초 안에 난 크래시는 다음
실행에서 가장 먼저 전송됩니다.

`AtlasCrash.setEnabled(false)`는 수집을 멈추고 그 선택을 기억합니다. 동의 화면에 씁니다.
`AtlasCrash.crashedLastRun()`은 직전 실행이 크래시로 끝났는지 알려 줍니다.

### 읽을 수 있는 스택 트레이스 (난독화 빌드)

R8로 난독화한 빌드는 난독화된 프레임을 보냅니다. 빌드마다 id를 하나 만들어
매니페스트에 싣고, 그 빌드의 `mapping.txt`를 같은 id로 업로드합니다.
릴리스가 사용자에게 닿기 전에 업로드합니다. 난독화된 채 묶인 크래시는 별개의 이슈로 남습니다.

```gradle title="app/build.gradle"
// app/build.gradle: 빌드마다 새 id를 만들어 매니페스트에 넘깁니다.
def atlasMappingId = UUID.randomUUID().toString()

android {
    defaultConfig {
        manifestPlaceholders.atlasMappingId = atlasMappingId
    }
}

// 빌드와 같은 실행에서 돌려야 같은 id를 봅니다: ./gradlew assembleRelease uploadAtlasMapping
// ATLAS_API_TOKEN은 App Atlas API 액세스 토큰이며 SDK 키가 아닙니다.
tasks.register('uploadAtlasMapping', Exec) {
    mustRunAfter 'assembleRelease'
    commandLine 'curl', '--fail', '-X', 'POST',
            "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=${android.defaultConfig.applicationId}&debugId=${atlasMappingId}",
            '-H', "Authorization: Bearer ${System.getenv('ATLAS_API_TOKEN')}",
            '--data-binary', "@${buildDir}/outputs/mapping/release/mapping.txt"
}
```

```xml title="AndroidManifest.xml"
<!-- AndroidManifest.xml, <application> 안 -->
<meta-data android:name="dev.appatlas.sdk.mappingId" android:value="${atlasMappingId}"/>
```

### 네이티브 스택 트레이스

네이티브 크래시는 build id와 모듈 상대 주소로 보고됩니다. 배포하는 `.so`를 스트립하지 않은 채 올리면
서버가 함수, 파일, 행으로 복원합니다. id는 라이브러리 자체의 GNU build id이며 서버가 파일에서 읽으므로
파일만 올리면 됩니다.

```sh title="upload-native-symbols.sh"
# CI, assembleRelease 다음. 배포하는 라이브러리마다 한 번. 스트립 전 .so는
# build/intermediates/merged_native_libs(또는 cmake의 obj 디렉터리)에 있고 APK에는 스트립된 것이 들어갑니다.
for so in app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/*/*.so; do
  curl --fail -X POST \
    "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=$PACKAGE_NAME&kind=elf" \
    -H "Authorization: Bearer $ATLAS_API_TOKEN" \
    --data-binary "@$so"
done
```

## 프라이버시

SDK는 설치 단위의 난수 id 하나를 만들 뿐, 기기 식별자나 광고 식별자를 읽지
않습니다. GAID도, 하드웨어 id도, 삭제 후에 남는 어떤 것도 읽지 않습니다.
함께 보내는 기기 정보(OS 버전, 모델, 로캘, 시간대, 앱 버전, 설치 출처)는
일반적인 크래시 리포트 항목이며 누구도 특정하지 않습니다.

<!-- guide:end -->

## 검사

```sh
sh check-core.sh                             # JVM 반쪽들, 실제 시그널에 대한 C 핸들러, 골든 바이트 비교
ATLAS_SERVER=../app-atlas sh check-core.sh   # 서버의 실제 파서까지
sh tools/device-check.sh                     # 샘플을 빌드해 기기·에뮬레이터에서 모든 방식으로 죽이고 봉투를 뽑는다
```

MIT.
