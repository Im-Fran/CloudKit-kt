# Contributing to CloudKit-kt

Thanks for being here. CloudKit-kt is a native Kotlin client for CloudKit Web
Services that mirrors the CloudKit JS API, so an Android app can work with the
same iCloud data as its iOS counterpart.

## Ground rules

- **Follow CloudKit JS.** New API should look like its CloudKit JS equivalent
  (names, parameters, semantics), adapted to idiomatic Kotlin: `suspend`
  functions, `Flow` for paginated results, typed values.
- **Keep the core plain Kotlin/JVM.** Anything Android-specific belongs in
  `cloudkit-android`, never in `cloudkit`.
- **Explicit API mode is on.** Every public declaration needs an explicit
  visibility and type; keep the public surface small.
- **Credentials are sensitive.** Never log tokens or keys, and store them only
  through `CloudKitStore` implementations.
- **Don't add dependencies lightly.** Prefer what is already in
  `gradle/libs.versions.toml`.

## Getting set up

You need JDK 17+ (CI uses 21) and the Android SDK with platform 37:

```sh
git clone https://github.com/Im-Fran/CloudKit-kt.git
cd CloudKit-kt
./gradlew build
```

Everyday commands:

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compile everything and run all tests (what CI runs) |
| `./gradlew :cloudkit:test` | Run the core unit tests only |
| `./gradlew :sample:installDebug` | Install the sample app on a device or emulator |
| `./gradlew publishToMavenLocal` | Publish the artifacts to `~/.m2` for local testing |

CI runs `./gradlew build`, and it must pass before a PR can land.

## Where things live

```
cloudkit/                  Pure Kotlin/JVM core
  CloudKit.kt              configure() entry point
  Container.kt, Database.kt
  auth/                    server-to-server request signing
  model/                   field values, record DSL, API types
  sync/                    incremental sync with change tokens
  internal/                HTTP layer (not public API)
cloudkit-android/          Custom Tabs sign-in, Keystore-backed PreferencesStore
sample/                    Minimal Compose app
gradle/libs.versions.toml  Dependency versions
```

Tests use OkHttp's `MockWebServer`; new endpoints should come with a test that
checks the request CloudKit receives and the parsing of its response.

## Branches and commits

Branch off `dev`, which is the default branch:

```sh
git checkout dev && git pull
git checkout -b feat/zone-wide-sharing
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org),
written in the imperative:

```
feat(sync): emit deleted zones from CloudKitSync
fix(auth): accept PKCS#8 keys in ServerToServerSigner
docs: document the App Links callback setup
```

Common types: `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`,
`chore`. Scopes follow the area touched (`core`, `android`, `auth`, `sync`,
`model`, `sample`, …).

PRs to `dev` are squash-merged. Releases are cut by merging `dev` into `prd`
with a merge commit.

## Opening a pull request

1. Make sure `./gradlew build` passes.
2. Push your branch and open a PR against `dev`.
3. Fill in the PR template.
4. Keep the PR focused. Unrelated cleanups belong in their own PR.

## Reporting bugs and requesting features

Use the [issue templates](https://github.com/Im-Fran/CloudKit-kt/issues/new/choose).
For API errors, the `serverErrorCode` and `reason` from the `CloudKitException`
make the difference between a guess and a fix — strip any tokens first.

Security vulnerabilities go through [SECURITY.md](SECURITY.md) instead — never
a public issue.

## Code of Conduct

Taking part in this project means following the
[Code of Conduct](CODE_OF_CONDUCT.md).
