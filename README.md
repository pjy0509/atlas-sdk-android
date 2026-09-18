# Atlas SDK for Android

[한국어](README.ko.md) · [中文](README.zh.md)

The client half of [App Atlas](https://appatlas.dev) on Android: deep links and
crash reporting. Pure Java, **minSdk 16** — the floor the incumbents walked away
from. Native crash capture is a separate artifact at 21, the NDK's own floor.

## Install

<!-- tabs:start -->
#### build.gradle

```gradle
dependencies {
    implementation 'dev.appatlas:atlas-links:0.2.0'
    implementation 'dev.appatlas:atlas-crash:0.2.0'
    implementation 'dev.appatlas:atlas-crash-ndk:0.2.0'   // only with C/C++ code
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
## Start

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: the class the manifest names as android:name on <application>.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // Modules (Links, Crash, later Push) wire in from the next line.
    }
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: the class the manifest names as android:name on <application>.
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");
        // Modules (Links, Crash, later Push) wire in from the next line.
    }
}
```
<!-- tabs:end -->

## Links

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: the class the manifest names as android:name on <application>.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")

        AtlasLinks.setListener { link ->
            // Direct opens and the deferred link arrive here alike.
            // link.deferred: true when the link crossed the install.
            // link.match: referrer / clipboard / campaign_id / relink.
            // Route with link.path and link.payload, e.g.:
            // link.path?.let { openScreen(it, link.payload) }
        }
    }
}
```

```kotlin title="MainActivity.kt"
// MainActivity.kt: the launcher activity. Stale-intent guards are built in.
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AtlasLinks.handle(intent)
}

// A singleTop activity is re-entered here instead.
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    AtlasLinks.handle(intent)
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: the class the manifest names as android:name on <application>.
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");

        AtlasLinks.setListener(new AtlasLinkListener() {
            @Override
            public void onLink(AtlasLink link) {
                // Direct opens and the deferred link arrive here alike.
                // link.deferred: true when the link crossed the install.
                // link.match: referrer / clipboard / campaign_id / relink.
                // Route with link.path and link.payload, e.g.:
                // if (link.path != null) openScreen(link.path, link.payload);
            }
        });
    }
}
```

```java title="MainActivity.java"
// MainActivity.java: the launcher activity. Stale-intent guards are built in.
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AtlasLinks.handle(getIntent());
}

// A singleTop activity is re-entered here instead.
@Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    AtlasLinks.handle(intent);
}
```
<!-- tabs:end -->

A deferred link needs no extra call: the first launch reads the Play install
referrer once, claims the token it carries, and the same listener receives the
link. `AtlasLinks.firstReferringLink()` returns the link that produced the
install, forever, for referral rewards.

## Crash

<!-- tabs:start -->
#### Kotlin

```kotlin title="MyApplication.kt"
// MyApplication.kt: the class the manifest names as android:name on <application>.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.start(this, "sdk_…")
        // Crashes, ANRs and kills are caught from this line on. The rest is optional.

        // Your own id for the signed-in user, and the state worth seeing beside a crash.
        AtlasCrash.setUserId("u-123")
        AtlasCrash.setKey("screen", "checkout")
        AtlasCrash.leaveBreadcrumb("cart", "add")
        AtlasCrash.log("cart total recomputed")
    }
}
```

```kotlin title="CheckoutActivity.kt"
// CheckoutActivity.kt: anywhere an exception is caught but still worth knowing about.
private fun pay() {
    try {
        check(intent.hasExtra("cart")) { "cart is missing" }
    } catch (error: IllegalStateException) {
        AtlasCrash.recordError(error)
        // The app's own recovery goes here. Example:
        // showRetry()
    }
}
```

#### Java

```java title="MyApplication.java"
// MyApplication.java: the class the manifest names as android:name on <application>.
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Atlas.start(this, "sdk_…");
        // Crashes, ANRs and kills are caught from this line on. The rest is optional.

        // Your own id for the signed-in user, and the state worth seeing beside a crash.
        AtlasCrash.setUserId("u-123");
        AtlasCrash.setKey("screen", "checkout");
        AtlasCrash.leaveBreadcrumb("cart", "add");
        AtlasCrash.log("cart total recomputed");
    }
}
```

```java title="CheckoutActivity.java"
// CheckoutActivity.java: anywhere an exception is caught but still worth knowing about.
private void pay() {
    try {
        if (!getIntent().hasExtra("cart")) {
            throw new IllegalStateException("cart is missing");
        }
    } catch (IllegalStateException error) {
        AtlasCrash.recordError(error);
        // The app's own recovery goes here. Example:
        // showRetry();
    }
}
```
<!-- tabs:end -->

What is caught, with no call beyond `Atlas.start`:

| Death | How it is caught |
|---|---|
| An uncaught exception on any thread, `OutOfMemoryError` included | The process-wide handler, chained ahead of whoever held it |
| An ANR | Android 11+: the OS's own exit record, with its thread dump. Below: a main-looper watchdog, cross-checked against the system's error state so a paused debugger never counts |
| A kill from outside, a low-memory kill, an excessive-resource kill | The OS's exit record, at the next start |
| A native signal in C or C++ code (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL), from any thread | `atlas-crash-ndk`: an async-signal-safe handler, sent at the next start |

A crash is written to disk on the dying thread and sent at the next start, together with
the end of its session, which is what crash-free sessions are counted from. Every report
carries the last 100 breadcrumbs, up to 64 keys, the newest 64 KB of `AtlasCrash.log`
lines, and the device's state at that moment: free memory and disk, foreground or not,
the activity on screen. A crash within five seconds of start is sent first thing at the
next start.

`AtlasCrash.setEnabled(false)` stops collection and remembers the choice, for a consent
screen. `AtlasCrash.crashedLastRun()` says whether the previous run ended in a crash.

## Modules

| Artifact | What it is | minSdk |
|---|---|---|
| `atlas-core` | Envelopes, the disk queue, the sender. Every module rides it. | 16 |
| `atlas-links` | Deep-link inflow: deferred claims and direct opens. | 16 |
| `atlas-crash` | Crash reporting: uncaught exceptions, handled errors, ANRs, kills, sessions. | 16 |
| `atlas-crash-ndk` | Native signal capture for C/C++ code: SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL. | 21 |

`atlas-links` brings the Play install-referrer library with it, so the
deferred link works with the one dependency above.

## Readable stack traces (minified builds)

A build minified by R8 reports obfuscated frames. Give each build an id, ship it in
the manifest, and upload that build's `mapping.txt` under the same id. Upload before
the release reaches users: a crash grouped while obfuscated stays a separate issue.

```gradle title="app/build.gradle"
// app/build.gradle: a fresh id per build, handed to the manifest.
def atlasMappingId = UUID.randomUUID().toString()

android {
    defaultConfig {
        manifestPlaceholders.atlasMappingId = atlasMappingId
    }
}

// Run with the build, so both see the same id: ./gradlew assembleRelease uploadAtlasMapping
// ATLAS_API_TOKEN is an App Atlas API access token, never the SDK key.
tasks.register('uploadAtlasMapping', Exec) {
    mustRunAfter 'assembleRelease'
    commandLine 'curl', '--fail', '-X', 'POST',
            "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=${android.defaultConfig.applicationId}&debugId=${atlasMappingId}",
            '-H', "Authorization: Bearer ${System.getenv('ATLAS_API_TOKEN')}",
            '--data-binary', "@${buildDir}/outputs/mapping/release/mapping.txt"
}
```

```xml title="AndroidManifest.xml"
<!-- AndroidManifest.xml, inside <application> -->
<meta-data android:name="dev.appatlas.sdk.mappingId" android:value="${atlasMappingId}"/>
```

## Native stack traces

A native crash is reported as build id plus module-relative addresses. Upload each `.so` you ship,
unstripped, and the server resolves function, file and line. The id is the library's own GNU build id;
the server reads it from the file, so only the file is needed.

```sh title="upload-native-symbols.sh"
# CI, after assembleRelease: one call per shipped library. The unstripped .so is under
# build/intermediates/merged_native_libs (or cmake's obj dir); the APK carries the stripped one.
for so in app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/*/*.so; do
  curl --fail -X POST \
    "https://appatlas.dev/api/ingest/symbols?store=play-store&appId=$PACKAGE_NAME&kind=elf" \
    -H "Authorization: Bearer $ATLAS_API_TOKEN" \
    --data-binary "@$so"
done
```

## Deep-link detection (optional)

For `navigator.getInstalledRelatedApps()` to answer "installed" on the visit
page instead of guessing, the app must vouch for the link's origin. Add to
`AndroidManifest.xml` inside `<application>`:

```xml title="AndroidManifest.xml"
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml title="res/values/strings.xml"
<!-- res/values/strings.xml — if you already have asset_statements, add the
     object below to your existing array instead. -->
<string name="asset_statements" translatable="false">
  [{
    \"relation\": [\"delegate_permission/common.handle_all_urls\"],
    \"target\": {\"namespace\": \"web\", \"site\": \"https://appatlas.dev\"}
  }]
</string>
```

## Privacy

The SDK mints an install-scoped random id and reads no device or advertising
identifier — not GAID, not the hardware id, nothing that survives an uninstall.
Device context (OS version, model, locale, timezone, app version, installer) is
the standard crash-report set and identifies no one.
<!-- guide:end -->

## Checks

```sh
sh check-core.sh                             # the JVM halves, the C handler against real signals, the golden bytes
ATLAS_SERVER=../app-atlas sh check-core.sh   # and the server's own parser
sh tools/device-check.sh                     # builds the sample, dies every way on a device or emulator, pulls the envelopes
```

MIT.
