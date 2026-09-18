# Atlas SDK for Android

[English](README.md) · [한국어](README.ko.md)

[App Atlas](https://appatlas.dev) 的 Android 客户端：深层链接与崩溃上报。纯 Java，
**minSdk 16** 起支持。这是其他 SDK 早已放弃的下限。原生崩溃捕获以单独构件提供，
下限为 NDK 自身的 21。

## 安装

<!-- tabs:start -->
#### build.gradle

```gradle
dependencies {
    implementation 'dev.appatlas:atlas-links:0.2.0'
    implementation 'dev.appatlas:atlas-crash:0.2.0'
    implementation 'dev.appatlas:atlas-crash-ndk:0.2.0'   // 仅当有 C/C++ 代码时
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
## 启动

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 清单 <application> 的 android:name 所注册的类。
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // 各模块（Links、Crash，之后的 Push）从下一行开始接线。
    }
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: 清单 <application> 的 android:name 所注册的类。
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");
        // 各模块（Links、Crash，之后的 Push）从下一行开始接线。
    }
}
```
<!-- tabs:end -->

## Links

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 清单 <application> 的 android:name 所注册的类。
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")

        AtlasLinks.setListener { link ->
            // 直接打开与延迟链接都到达这里。
            // link.deferred: 跨越了安装的链接为 true。
            // link.match: referrer / clipboard / campaign_id / relink。
            // 用 link.path 与 link.payload 做页面跳转，例如：
            // link.path?.let { openScreen(it, link.payload) }
        }
    }
}
```

```kotlin title="MainActivity.kt"
// MainActivity.kt: 启动器 Activity。旧 Intent 的重复投递已在内部拦截。
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AtlasLinks.handle(intent)
}

// singleTop 的 Activity 重新打开时走这里。
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    AtlasLinks.handle(intent)
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: 清单 <application> 的 android:name 所注册的类。
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");

        AtlasLinks.setListener(new AtlasLinkListener() {
            @Override
            public void onLink(AtlasLink link) {
                // 直接打开与延迟链接都到达这里。
                // link.deferred: 跨越了安装的链接为 true。
                // link.match: referrer / clipboard / campaign_id / relink。
                // 用 link.path 与 link.payload 做页面跳转，例如：
                // if (link.path != null) openScreen(link.path, link.payload);
            }
        });
    }
}
```

```java title="MainActivity.java"
// MainActivity.java: 启动器 Activity。旧 Intent 的重复投递已在内部拦截。
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AtlasLinks.handle(getIntent());
}

// singleTop 的 Activity 重新打开时走这里。
@Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    AtlasLinks.handle(intent);
}
```
<!-- tabs:end -->

延迟链接无需额外调用：首次启动会读取一次 Play install referrer，
兑换其中携带的令牌，同一个监听器随即收到链接。
`AtlasLinks.firstReferringLink()` 永久返回产生这次安装的链接，
可用于推荐奖励。

## Crash

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: 清单 <application> 的 android:name 所注册的类。
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // 从这一行起，崩溃、ANR 与 kill 会被收集。以下均为可选。

        // 已登录用户的自有 id，以及希望在崩溃旁看到的状态。
        AtlasCrash.setUserId("u-123")
        AtlasCrash.setKey("screen", "checkout")
        AtlasCrash.leaveBreadcrumb("cart", "add")
        AtlasCrash.log("cart total recomputed")
    }
}
```

```kotlin title="CheckoutActivity.kt"
// CheckoutActivity.kt：任何捕获了异常但仍值得知晓的位置。
private fun pay() {
    try {
        check(intent.hasExtra("cart")) { "cart is missing" }
    } catch (error: IllegalStateException) {
        AtlasCrash.recordError(error)
        // 应用自身的恢复处理写在这里。例如：
        // showRetry()
    }
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: 清单 <application> 的 android:name 所注册的类。
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");
        // 从这一行起，崩溃、ANR 与 kill 会被收集。以下均为可选。

        // 已登录用户的自有 id，以及希望在崩溃旁看到的状态。
        AtlasCrash.setUserId("u-123");
        AtlasCrash.setKey("screen", "checkout");
        AtlasCrash.leaveBreadcrumb("cart", "add");
        AtlasCrash.log("cart total recomputed");
    }
}
```

```java title="CheckoutActivity.java"
// CheckoutActivity.java：任何捕获了异常但仍值得知晓的位置。
private void pay() {
    try {
        if (!getIntent().hasExtra("cart")) {
            throw new IllegalStateException("cart is missing");
        }
    } catch (IllegalStateException error) {
        AtlasCrash.recordError(error);
        // 应用自身的恢复处理写在这里。例如：
        // showRetry();
    }
}
```
<!-- tabs:end -->

除 `Atlas.start` 外无需任何调用即可捕获：

| 崩溃方式 | 捕获方式 |
|---|---|
| 任意线程的未捕获异常，含 `OutOfMemoryError` | 进程级处理器，链接在原有处理器之前 |
| ANR | Android 11 及以上：系统自身的退出记录及线程转储。以下：主线程看门狗，并与系统错误状态交叉核对，调试器暂停不会计入 |
| 来自外部的 kill、低内存 kill、资源超限 kill | 下次启动时读取系统退出记录 |
| C/C++ 代码中的原生信号（SIGSEGV、SIGABRT、SIGBUS、SIGFPE、SIGILL），任意线程 | `atlas-crash-ndk`：async-signal-safe 处理器记录，下次启动时发送 |

崩溃会在即将终止的线程上先写入磁盘，并在下次启动时与会话的结束状态一同发送。
crash-free 会话即据此计算。每份报告都附带最近 100 条面包屑、最多 64 个键、
`AtlasCrash.log` 最新的 64KB，以及当时的设备状态：剩余内存与磁盘、是否在前台、
屏幕上的 Activity。启动后 5 秒内发生的崩溃会在下次启动时最先发送。

`AtlasCrash.setEnabled(false)` 会停止收集并记住该选择，可用于同意界面。
`AtlasCrash.crashedLastRun()` 返回上次运行是否以崩溃结束。

## 模块

| 构件 | 作用 | minSdk |
|---|---|---|
| `atlas-core` | 信封、磁盘队列、发送器。所有模块的基础。 | 16 |
| `atlas-links` | 深层链接流入：延迟兑换与直接打开。 | 16 |
| `atlas-crash` | 崩溃报告：未捕获异常、已处理错误、ANR、kill、会话。 | 16 |
| `atlas-crash-ndk` | C/C++ 代码的原生信号捕获：SIGSEGV、SIGABRT、SIGBUS、SIGFPE、SIGILL。 | 21 |

`atlas-links` 会一并带上 Play install-referrer 库，
因此上面这一个依赖就能让延迟链接工作。

## 可读的堆栈（混淆构建）

经 R8 混淆的构建会上报混淆后的帧。为每次构建生成一个 id 写入清单，
并以同一 id 上传该构建的 `mapping.txt`。
请在版本到达用户之前上传。以混淆状态归组的崩溃会保留为单独的问题。

```gradle title="app/build.gradle"
// app/build.gradle：每次构建生成新的 id，并交给清单。
def atlasMappingId = UUID.randomUUID().toString()

android {
    defaultConfig {
        manifestPlaceholders.atlasMappingId = atlasMappingId
    }
}

// 与构建在同一次执行中运行，二者才会看到同一个 id：./gradlew assembleRelease uploadAtlasMapping
// ATLAS_API_TOKEN 是 App Atlas API 访问令牌，而不是 SDK 密钥。
tasks.register('uploadAtlasMapping', Exec) {
    mustRunAfter 'assembleRelease'
    commandLine 'curl', '--fail', '-X', 'POST',
            "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=${android.defaultConfig.applicationId}&debugId=${atlasMappingId}",
            '-H', "Authorization: Bearer ${System.getenv('ATLAS_API_TOKEN')}",
            '--data-binary', "@${buildDir}/outputs/mapping/release/mapping.txt"
}
```

```xml title="AndroidManifest.xml"
<!-- AndroidManifest.xml，<application> 内 -->
<meta-data android:name="dev.appatlas.sdk.mappingId" android:value="${atlasMappingId}"/>
```

## 原生堆栈

原生崩溃以 build id 和模块相对地址上报。上传你发布的、未剥离符号的 `.so`，服务器即可还原函数、文件与行号。
id 是库自身的 GNU build id，由服务器从文件中读取，因此只需上传文件。

```sh title="upload-native-symbols.sh"
# CI，assembleRelease 之后，每个发布的库一次。未剥离的 .so 位于
# build/intermediates/merged_native_libs（或 cmake 的 obj 目录），APK 中的是剥离后的版本。
for so in app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/*/*.so; do
  curl --fail -X POST \
    "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=$PACKAGE_NAME&kind=elf" \
    -H "Authorization: Bearer $ATLAS_API_TOKEN" \
    --data-binary "@$so"
done
```

## 深层链接检测（可选）

要让访问页面上的 `navigator.getInstalledRelatedApps()` 明确回答"已安装"
而不是猜测，应用需要为链接来源作保。在 `AndroidManifest.xml` 的
`<application>` 内添加：

```xml title="AndroidManifest.xml"
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml title="res/values/strings.xml"
<!-- res/values/strings.xml — 如果已有 asset_statements，把下面的对象
     加进现有数组即可。 -->
<string name="asset_statements" translatable="false">
  [{
    \"relation\": [\"delegate_permission/common.handle_all_urls\"],
    \"target\": {\"namespace\": \"web\", \"site\": \"https://appatlas.dev\"}
  }]
</string>
```

## 隐私

SDK 只生成一个安装范围内的随机 id，不读取任何设备或广告标识符：
不读 GAID，不读硬件 id，不读任何卸载后仍然存在的东西。
随附发送的设备信息（系统版本、机型、区域、时区、应用版本、安装来源）
是常见的崩溃报告字段，不指向任何人。
<!-- guide:end -->

## 检查

```sh
sh check-core.sh                             # JVM 两半、真实信号下的 C 处理器、黄金字节比对
ATLAS_SERVER=../app-atlas sh check-core.sh   # 再加服务器的真实解析器
sh tools/device-check.sh                     # 构建示例，在设备或模拟器上以每种方式崩溃，并取出信封
```

MIT.
