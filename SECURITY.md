# Security policy

## Supported versions

There are no binary releases: fixes go to the `main` branch only, so always build from the latest commit.

## Reporting a vulnerability

Please **don't open a public issue** for security problems.
Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository.

Include the commit you built from, the device model and Fire OS version, and the steps to reproduce.
This is a personal project maintained in spare time: expect a first reply within a couple of weeks.

## Scope

NTSFireTV has no server and no telemetry. Network access is used only for the optional weather
(Open-Meteo) and, at build time, to download the TV Bro APK (verified with a pinned SHA-256).
Relevant reports include, for example: other apps reading NTSFireTV files through its content provider,
malicious theme/backup JSON files crashing or misusing the launcher, or Gradle tasks running unexpected commands.
