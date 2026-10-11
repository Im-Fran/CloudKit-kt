# Security Policy

## Supported versions

CloudKit-kt has not had a stable release yet. Fixes land on `dev`, reach `prd`
with the next release and ship in the next published version; older versions
are not patched.

| Version | Supported |
| --- | --- |
| Latest release | ✅ |
| Anything older | ❌ |

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub:
[**Report a vulnerability**](https://github.com/Im-Fran/CloudKit-kt/security/advisories/new)
(repository → **Security** → **Advisories** → **Report a vulnerability**).

Helpful things to include:

- What the issue is, and what an attacker gets out of it.
- Steps to reproduce, or a proof of concept.
- The CloudKit-kt version, and whether you use `cloudkit` on the JVM or
  `cloudkit-android` (with the Android version).

What to expect:

- An acknowledgement within a few days.
- An assessment and a plan once the report is confirmed.
- A fix in the next release, with credit in the advisory unless you would
  rather not be named.

Please give a reasonable window for a fix before disclosing publicly.

## Scope

CloudKit-kt is a client library: it handles credentials and talks to CloudKit
Web Services on behalf of the app that embeds it. In scope, for example:

- Leaking or mishandling the web auth token, API token or server-to-server
  private key (logging it, storing it unencrypted, sending it to the wrong host).
- Flaws in server-to-server request signing.
- Weaknesses in `PreferencesStore` encryption or the Android Keystore usage.
- Accepting a sign-in callback that was not started by the app.
- A flaw in how release artifacts are built, signed or published.

Out of scope: vulnerabilities in CloudKit or iCloud themselves, in the
underlying dependencies (report those upstream), in the sample app's demo
configuration, or issues that require an already-compromised device or JVM.
