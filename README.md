<div align="center">

# ☁️ CloudKit-kt

**A native Kotlin client for CloudKit Web Services, so your Android app can sync with the same iCloud data as your iOS app.**

[![License](https://img.shields.io/github/license/Im-Fran/CloudKit-kt)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/Im-Fran/CloudKit-kt/ci.yml?branch=dev&label=CI)](https://github.com/Im-Fran/CloudKit-kt/actions)

</div>

---

## 📖 Overview

CloudKit-kt brings [CloudKit JS](https://developer.apple.com/documentation/cloudkitjs) to Android. It doesn't embed CloudKit JS in a WebView. It talks to **CloudKit Web Services**, the REST API that CloudKit JS wraps, from pure Kotlin.

The API follows CloudKit JS closely (`CloudKit.configure`, `container.privateCloudDatabase`, `performQuery`, `newRecordsBatch`, `fetchRecordZoneChanges`, …). Every call is a `suspend` function, paginated results are `Flow`s, and records use typed Kotlin values.

It's built for apps that already have an iOS version backed by CloudKit and need an Android version reading and writing the same containers. The core module is plain Kotlin/JVM, so it also works in backends and tools that use server-to-server keys.

---

## ✨ Features

- **Records** — save, fetch, delete and query, with continuation markers, `queryAll(): Flow<Record>` and batches using explicit operation types (`create`, `update`, `forceUpdate`, `replace`, `forceReplace`, `delete`, `forceDelete`)
- **Typed field values** — every CloudKit type (`STRING`, `INT64`, `DOUBLE`, `TIMESTAMP`, `BYTES`, `REFERENCE`, `ASSETID`, `LOCATION` and their `_LIST` forms), plus a small DSL: `record("Note") { this["title"] = "Hola" }`, `record.string("title")`
- **Incremental sync** — `CloudKitSync` walks `changes/database` → `changes/zone` and persists change tokens, emitting upserts, deletions and deleted zones
- **Zones and subscriptions** — create, fetch, list and delete
- **Assets** — upload (up to 15 MB) and download, plus `assets/rereference`
- **Sharing** — create `cloudkit.share` records, resolve share metadata, accept shares
- **Users** — current user identity and discovery by email, phone or record name
- **Notifications** — subscription notifications through CloudKit's web long-poll channel, exposed as a `Flow`
- **Authentication**
  - **API token + iCloud sign-in** — the web auth token is persisted and rotated, and `userIdentity` becomes `null` when CloudKit rejects it
  - **Server-to-server keys** — ECDSA P-256 request signing; accepts `openssl`'s SEC1 PEM directly
- **Android sign-in** — Apple's sign-in page opens in a Custom Tab, and the callback is only accepted while a sign-in is in progress
- **Encrypted storage** — `PreferencesStore` keeps tokens in SharedPreferences, encrypted with an AES-GCM key held in the Android Keystore
- **Resilience** — per-item errors in `Results<T>`, typed `CloudKitException`, and automatic retries on `THROTTLED` / `TRY_AGAIN_LATER` using `retryAfter`

---

## 🛠 Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.4.21 (explicit API mode) |
| HTTP | OkHttp 5.5.0 (+ `okhttp-coroutines`) |
| Serialization | kotlinx.serialization 1.11.0 |
| Concurrency | kotlinx.coroutines 1.11.0 |
| Android | AGP 9.4.1, AndroidX Browser (Custom Tabs), Android Keystore |
| Sample app | Jetpack Compose (Material 3) |
| Build | Gradle 9.8.1 (Kotlin DSL, version catalog) |
| Testing | JUnit 6, kotlin.test, OkHttp MockWebServer |
| Publishing | `com.vanniktech.maven.publish` → Maven Central |

---

## 📦 Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [`cloudkit`](cloudkit) | `io.github.im-fran:cloudkit` | Pure Kotlin/JVM core. Works on Android and on the server. |
| [`cloudkit-android`](cloudkit-android) | `io.github.im-fran:cloudkit-android` | Custom Tabs sign-in and Keystore-backed `PreferencesStore`. Includes the core. |
| [`sample`](sample) | — | Minimal Compose app: sign in, list and add `Note` records. |

---

## 📋 Requirements

- **JDK 17+** (CI uses 21)
- **Android SDK** with platform **37** (`compileSdk 37`, `minSdk 26`)
- An Apple Developer account with a **CloudKit container**
- **Git**

---

## 🚀 Getting Started

### 1. Add the dependency

> Not published to Maven Central yet (current version: `0.1.0-SNAPSHOT`). Until it is, build from source (below) and use `./gradlew publishToMavenLocal`.

```kotlin
dependencies {
    implementation("io.github.im-fran:cloudkit-android:<version>") // Android
    // implementation("io.github.im-fran:cloudkit:<version>")      // JVM only
}
```

### 2. Set up CloudKit Console

1. In [CloudKit Console](https://icloud.developer.apple.com/), open your container → **Tokens & Keys**, and create an **API token**.
2. Set the token's **Sign In Callback** to a URL your app handles, for example `myapp-cloudkit://auth`.
3. Deploy the schema to Production before you use `Environment.PRODUCTION`. Fields you filter or sort on must be indexed (Queryable / Sortable).

### 3. Configure the container

```kotlin
val container = CloudKit.configure(
    ContainerConfig(
        containerIdentifier = "iCloud.com.example.app",
        environment = Environment.PRODUCTION,
        auth = Auth.ApiToken("<api token>", PreferencesStore(context)),
    ),
).getDefaultContainer()
```

### 4. Sign the user in (Android)

```kotlin
// app/build.gradle.kts
android.defaultConfig.manifestPlaceholders["cloudkitAuthScheme"] = "myapp-cloudkit"
```

```kotlin
lifecycleScope.launch {
    val user = CloudKitSignIn.signIn(this@MainActivity, container) // opens Apple's sign-in page if needed
}
```

> [!WARNING]
> Any installed app can declare the same custom scheme and receive the redirect, `ckWebAuthToken` included. For production, use a verified **https App Link** as the Sign In Callback (e.g. `https://example.com/cloudkit/auth`). Android only delivers those to the app that owns the domain. Add the filter to the library's activity in your app manifest, and publish `/.well-known/assetlinks.json` on the domain:
>
> ```xml
> <activity android:name="io.github.imfran.cloudkit.android.CloudKitAuthCallbackActivity" tools:node="merge">
>     <intent-filter android:autoVerify="true">
>         <action android:name="android.intent.action.VIEW" />
>         <category android:name="android.intent.category.DEFAULT" />
>         <category android:name="android.intent.category.BROWSABLE" />
>         <data android:scheme="https" android:host="example.com" android:pathPrefix="/cloudkit/auth" />
>     </intent-filter>
> </activity>
> ```
>
> Callbacks are only accepted while `signIn()` is waiting (10 minutes at most). Show the returned user (`userIdentity`) so they can spot a sign-in into an account that isn't theirs.

Without the Android module: `container.setUpAuth()` returns the user, or `null` and puts the URL to open in `container.signInUrl`. Pass the callback URL to `container.completeSignIn(url)` when the user comes back.

---

## 💡 Usage

### Records and queries

```kotlin
val db = container.privateCloudDatabase
val zone = ZoneID("Notes")

val saved = db.saveRecord(record("Note", zoneID = zone) {
    this["title"] = "Hola"
    this["createdAt"] = Instant.now()
    this["done"] = false // booleans are stored as INT64 0/1, like on iOS
})

db.saveRecord(saved.edit { this["done"] = true }) // update, uses recordChangeTag
db.deleteRecords(listOf(saved))

val page = db.performQuery(
    Query("Note", filterBy = listOf(filter("title", Comparator.BEGINS_WITH, "Ho")), sortBy = listOf(Sort("title"))),
    QueryOptions(zoneID = zone, resultsLimit = 50),
)

db.queryAll(Query("Note"), QueryOptions(zoneID = zone)).collect { println(it.string("title")) }

db.newRecordsBatch().create(a).forceUpdate(b).delete(c).commit(ModifyOptions(zoneID = zone, atomic = true))
```

Calls on several items return `Results<T>`, which has `results` and per-item `errors`; `getOrThrow()` throws the first error. Request-level failures throw `CloudKitException`.

### Syncing with the iOS app

```kotlin
val sync = CloudKitSync(container.privateCloudDatabase, PreferencesStore(context, "sync"))
sync.changes().collect { change ->
    when (change) {
        is SyncChange.Upserted -> dao.upsert(change.record)
        is SyncChange.Deleted -> dao.delete(change.recordName)
        is SyncChange.ZoneDeleted -> dao.deleteZone(change.zoneID)
    }
}
```

Change tokens only exist for custom zones in the private and shared databases, the same rule as on iOS. For the public database, query by `modifiedTimestamp` with `systemFilter("modifiedTimestamp", Comparator.GREATER_THAN, lastSync)`.

### Zones, subscriptions and notifications

```kotlin
db.saveRecordZones(listOf(ZoneID("Notes")))
db.saveSubscriptions(listOf(Subscription(subscriptionID = "notes", subscriptionType = Subscription.Type.ZONE, zoneID = zone)))

container.notifications().collect { n -> if (n.subscriptionID == "notes") sync.changes().collect(::apply) }
```

`notifications()` long-polls CloudKit's web push channel, so it only runs while you collect it. APNs isn't available on Android; for background updates, trigger a sync from your own push (FCM) or from WorkManager.

### Assets

```kotlin
val uploaded = db.uploadAsset(recordType = "Photo", fieldName = "image", data = bytes)
db.saveRecord(record("Photo", recordName = uploaded.recordName) { this["image"] = uploaded.asset })

val data = db.downloadAsset(db.fetchRecord(uploaded.recordName).asset("image")!!)
```

### Sharing

```kotlin
val share = db.shareRecord(root, participants = listOf(
    ShareParticipant(UserIdentity(lookupInfo = UserLookupInfo(emailAddress = "friend@example.com")), Permission.READ_WRITE, ShareParticipant.Type.USER),
))
container.fetchShareMetadata(listOf(shortGuid)) // records/resolve
container.acceptShares(listOf(shortGuid))       // records/accept → appears in sharedCloudDatabase
```

### Server-to-server keys

For backends and tools only. Never ship a private key inside an app. They only work with the public database.

```kotlin
ContainerConfig("iCloud.com.example.app", Environment.DEVELOPMENT, Auth.ServerToServer(keyId, File("eckey.pem").readText()))
```

The PEM is the output of `openssl ecparam -name prime256v1 -genkey -noout -out eckey.pem`.

### CloudKit JS → CloudKit-kt

| CloudKit JS | CloudKit-kt |
|---|---|
| `CloudKit.configure({containers})` | `CloudKit.configure(ContainerConfig(…))` |
| `container.setUpAuth()` / `whenUserSignsIn()` / `whenUserSignsOut()` | Same names, `suspend` |
| `database.saveRecords` / `fetchRecords` / `deleteRecords` / `performQuery` / `newRecordsBatch` | Same names |
| `database.fetchDatabaseChanges` / `fetchRecordZoneChanges` | Same names, plus `CloudKitSync` |
| `database.saveRecordZones` / `fetchRecordZones` / `fetchAllRecordZones` / `deleteRecordZones` | Same names |
| `database.saveSubscriptions` / `fetchSubscriptions` / `fetchAllSubscriptions` / `deleteSubscriptions` | Same names |
| `container.registerForNotifications` + `addNotificationListener` | `container.notifications(): Flow` |
| `container.fetchCurrentUserIdentity` / `discover…` | Same names |
| `database.shareWithUI` | `database.shareRecord` (no UI) |
| `container.acceptShares` / `fetchShareMetadata` | Same names |

---

## 🧑‍💻 Development

### 1. Clone the repository

```bash
git clone https://github.com/Im-Fran/CloudKit-kt.git
cd CloudKit-kt
```

### 2. Build and test

```bash
./gradlew build
```

The core's tests run against OkHttp's MockWebServer:

```bash
./gradlew :cloudkit:test
```

### 3. Run the sample app

Add your container and API token to `local.properties`:

| Property | Description | Example |
|----------|-------------|---------|
| `CLOUDKIT_CONTAINER` | CloudKit container identifier | `iCloud.com.example.app` |
| `CLOUDKIT_API_TOKEN` | API token from CloudKit Console | `abc123…` |

Set the token's Sign In Callback to `cloudkit-sample://auth`, create a `Note` record type with a Queryable/Sortable `title` field, and install the app:

```bash
./gradlew :sample:installDebug
```

---

## 🏗 Publishing

Both libraries are set up for Maven Central with `com.vanniktech.maven.publish`. The coordinates and POM metadata live in [`gradle.properties`](gradle.properties).

Install to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Publishing to Maven Central needs Central Portal credentials and a signing key (`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, … as Gradle properties):

```bash
./gradlew publishToMavenCentral
```

---

## 🤝 Contributing

Contributions are welcome. The default branch is `dev`.

1. Fork the repo
2. Create a branch: `git checkout -b feat/your-feature`
3. Commit with [Conventional Commits](https://www.conventionalcommits.org/): `git commit -m "feat: add your feature"`
4. Make sure `./gradlew build` passes
5. Push and open a PR against `dev`

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Made with ☕ by <a href="https://franciscosolis.cl">Fran</a>
</div>
