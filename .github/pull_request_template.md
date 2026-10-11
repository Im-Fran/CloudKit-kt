## What this changes

<!-- One or two sentences. What behaviour is different after this PR? -->

## Why

<!-- The problem being solved. Link the issue: Closes #123 -->

## How

<!-- The approach, and anything a reviewer would otherwise have to reverse-engineer. -->

## Testing

- [ ] `./gradlew build` passes
- [ ] New or changed endpoints are covered by a `MockWebServer` test
- [ ] Tried it in the sample app or against a real container (say which)

## Screenshots

<!-- Only for changes to the sample app or the Android sign-in flow. -->

## Checklist

- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org)
- [ ] Public API follows CloudKit JS naming and is explicitly typed
- [ ] No Android dependencies in `cloudkit`, no tokens or keys in logs
- [ ] README updated if the API or configuration changed
