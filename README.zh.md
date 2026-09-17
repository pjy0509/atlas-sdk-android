# Atlas SDK for Android

[English](README.md) · [한국어](README.ko.md)

[App Atlas](https://appatlas.dev) 的 Android 客户端。纯 Java，
**minSdk 16** 起支持。这是其他 SDK 早已放弃的下限。

## 安装

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
        // 延迟链接到此为止：首次启动会自行读取 Play install referrer。
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
        // 延迟链接到此为止：首次启动会自行读取 Play install referrer。
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

## 模块

| 构件 | 作用 | minSdk |
|---|---|---|
| `atlas-core` | 信封、磁盘队列、发送器。所有模块的基础。 | 16 |
| `atlas-links` | 深层链接流入：延迟兑换与直接打开。 | 16 |

`atlas-links` 会一并带上 Play install-referrer 库，
因此上面这一个依赖就能让延迟链接工作。

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
sh check-core.sh                             # JVM 两半 + 黄金字节比对
ATLAS_SERVER=../app-atlas sh check-core.sh   # 再加服务器的真实解析器
```

MIT.
