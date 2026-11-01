# Architecture decision records

One file per decision. A record is never edited after it is accepted, except to change its
status; a changed mind is a new record that supersedes the old one.

Template: **Status**, **Context**, **Decision**, **Consequences**, **Alternatives considered**.

| # | Title | Status |
|---|---|---|
| 0001 | [Native stack](0001-native-stack.md) | Accepted, libraries amended by 0012 |
| 0002 | [Design language](0002-design-language.md) | Accepted |
| 0003 | [API client](0003-api-client.md) | Accepted, Retrofit dropped by 0012 |
| 0004 | [Authentication](0004-authentication.md) | Accepted |
| 0005 | [Notifications](0005-notifications.md) | Accepted |
| 0006 | [Modules that are not native](0006-non-native-modules.md) | Accepted |
| 0007 | [Offline behaviour](0007-offline.md) | Accepted |
| 0008 | [Staying in sync with upstream](0008-upstream-sync.md) | Accepted |
| 0009 | [Project identity](0009-project-identity.md) | Accepted, distribution amended by 0013 |
| 0010 | [MVP scope](0010-mvp-scope.md) | Accepted, widened by 0015 |
| 0011 | [Strings come from upstream](0011-strings-from-upstream.md) | Accepted |
| 0012 | [Fewer libraries than first planned](0012-fewer-libraries.md) | Accepted (supersedes parts of 0001, 0003) |
| 0013 | [APK releases on GitHub](0013-apk-releases.md) | Accepted (amends 0009), trigger and version code superseded by 0014 |
| 0014 | [Continuous delivery of the APK](0014-continuous-delivery.md) | Accepted (supersedes parts of 0013) |
| 0015 | [Five more native modules, each in its own package](0015-more-native-modules.md) | Accepted (widens 0010) |
