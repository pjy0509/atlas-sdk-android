# Atlas SDK for Android

[한국어](README.ko.md) · [中文](README.zh.md)

The client half of [App Atlas](https://appatlas.dev) on Android. Pure Java,
**minSdk 16** — the floor the incumbents walked away from.

## Install

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

```kotlin
// Application.onCreate
Atlas.start(this, "sdk_…")

AtlasLinks.setListener { link ->
    // link.payload / link.path / link.deferred / link.match
    // link.channel / link.campaign / link.shortId
}
```

```kotlin
// The launcher activity, on every start — stale-intent guards are built in.
AtlasLinks.handle(intent)
```

#### Java

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
// The launcher activity, on every start — stale-intent guards are built in.
AtlasLinks.handle(getIntent());
```
<!-- tabs:end -->

A deferred link needs no extra call: the first launch reads the Play install
referrer once, claims the token it carries, and the same listener receives the
link. `AtlasLinks.firstReferringLink()` returns the link that produced the
install, forever, for referral rewards.

## Modules

| Artifact | What it is | minSdk |
|---|---|---|
| `atlas-core` | Envelopes, the disk queue, the sender. Every module rides it. | 16 |
| `atlas-links` | Deep-link inflow: deferred claims and direct opens. | 16 |

`atlas-links` brings the Play install-referrer library with it, so the
deferred link works with the one dependency above.

## Deep-link detection (optional)

For `navigator.getInstalledRelatedApps()` to answer "installed" on the visit
page instead of guessing, the app must vouch for the link's origin. Add to
`AndroidManifest.xml` inside `<application>`:

```xml
<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>
```

```xml
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
sh check-core.sh                             # the JVM halves + the golden bytes
ATLAS_SERVER=../app-atlas sh check-core.sh   # and the server's own parser
```

MIT.
