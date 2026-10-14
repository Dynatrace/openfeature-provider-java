# Changelog

## [0.5.1-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.5.0-alpha...v0.5.1-alpha) (2026-10-14)


### ✨ New Features

* sse test case implementation (ref: AI-724) ([#36](https://github.com/Dynatrace/openfeature-provider-java/issues/36)) ([50bf529](https://github.com/Dynatrace/openfeature-provider-java/commit/50bf529b735df64104d32fc28815d75696baf5f7))


### 🐛 Bug Fixes

* bound fetch duration and enforce single-flight CDN fetches ([#37](https://github.com/Dynatrace/openfeature-provider-java/issues/37)) ([ef0673d](https://github.com/Dynatrace/openfeature-provider-java/commit/ef0673dd7082ff4f887efc8245d2fcbdaaa12000))


### 🧹 Chore

* **deps:** bump com.fasterxml.jackson.core:jackson-databind from 2.22.1 to 2.22.3 ([#41](https://github.com/Dynatrace/openfeature-provider-java/issues/41)) ([1c2a1a6](https://github.com/Dynatrace/openfeature-provider-java/commit/1c2a1a6310d416133d93acd8374bbc3fc267ccb7))
* **deps:** bump local-demo provider pin to 0.5.0-alpha ([#32](https://github.com/Dynatrace/openfeature-provider-java/issues/32)) ([abf97a5](https://github.com/Dynatrace/openfeature-provider-java/commit/abf97a5e96165473ad3fb55d411dc769f7066aee))
* update the metrics url for LocalDemo.java NOISSUE ([#34](https://github.com/Dynatrace/openfeature-provider-java/issues/34)) ([b340003](https://github.com/Dynatrace/openfeature-provider-java/commit/b3400034deec7470b53b826a716fef382a2cf01c))


### 📚 Documentation

* align README with the standard provider format ([#35](https://github.com/Dynatrace/openfeature-provider-java/issues/35)) ([2c74f36](https://github.com/Dynatrace/openfeature-provider-java/commit/2c74f3686f8475f365a52142f21370b429cb717f))

## [0.5.0-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.4.4-alpha...v0.5.0-alpha) (2026-08-27)


### ⚠ BREAKING CHANGES

* rename metricsUrl to metricsOrigin, accept origin-only value ([#31](https://github.com/Dynatrace/openfeature-provider-java/issues/31))

### ✨ New Features

* rename metricsUrl to metricsOrigin, accept origin-only value ([#31](https://github.com/Dynatrace/openfeature-provider-java/issues/31)) ([e4e8179](https://github.com/Dynatrace/openfeature-provider-java/commit/e4e81797ae5d98454f59d652a06c05f4f36bf58a))
* **test:** introduce fm-provider-test-harness submodule (ICP-5904) - [#26](https://github.com/Dynatrace/openfeature-provider-java/issues/26) ([#30](https://github.com/Dynatrace/openfeature-provider-java/issues/30)) ([7a07774](https://github.com/Dynatrace/openfeature-provider-java/commit/7a077746373d46b47baf7aed2e7b0e297d1fef4b))


### 🐛 Bug Fixes

* validate configOrigin is a bare origin, not just path-free ([#27](https://github.com/Dynatrace/openfeature-provider-java/issues/27)) ([57dd4b8](https://github.com/Dynatrace/openfeature-provider-java/commit/57dd4b86969af663384a3056676ff17accdce223))


### 🧹 Chore

* **deps:** bump com.fasterxml.jackson.core:jackson-databind from 2.22.0 to 2.22.1 ([#25](https://github.com/Dynatrace/openfeature-provider-java/issues/25)) ([f4f332e](https://github.com/Dynatrace/openfeature-provider-java/commit/f4f332ea47f673869ef7d34e4d2f9e4dc9d27a0a))

## [0.4.4-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.4.3-alpha...v0.4.4-alpha) (2026-07-17)


### 🐛 Bug Fixes

* ICP-4502: Fix message body parsing to allow nested `.data` ([#23](https://github.com/Dynatrace/openfeature-provider-java/issues/23)) ([e316d03](https://github.com/Dynatrace/openfeature-provider-java/commit/e316d033dd484b0fce5cb534f2a90dd0d8eea3e9))

## [0.4.3-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.4.2-alpha...v0.4.3-alpha) (2026-07-13)


### ✨ New Features

* ICP-4502: Implement the remaining spec for SSE host parsing. ([#21](https://github.com/Dynatrace/openfeature-provider-java/issues/21)) ([9ed09a3](https://github.com/Dynatrace/openfeature-provider-java/commit/9ed09a3687a233660454133e6a6dd2a82e6719a4))

## [0.4.2-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.4.1-alpha...v0.4.2-alpha) (2026-07-13)


### ✨ New Features

* **sse:** SSE Support ([#11](https://github.com/Dynatrace/openfeature-provider-java/issues/11)) ([9f3c614](https://github.com/Dynatrace/openfeature-provider-java/commit/9f3c61442c75c76d7a3e77a8f2d6d8b2c3f9541b))


### 🧹 Chore

* **deps:** bump com.fasterxml.jackson.core:jackson-databind from 2.21.2 to 2.22.0 ([#20](https://github.com/Dynatrace/openfeature-provider-java/issues/20)) ([932a218](https://github.com/Dynatrace/openfeature-provider-java/commit/932a2187da40a59bc6b9557a136d1d0c6cb70482))

## [0.4.1-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.4.0-alpha...v0.4.1-alpha) (2026-06-01)


### ✨ New Features

* metric aggregation primitives and hook (1/2) ref: ICP-5377 ([#14](https://github.com/Dynatrace/openfeature-provider-java/issues/14)) ([e8a2952](https://github.com/Dynatrace/openfeature-provider-java/commit/e8a2952bcc2083623d6404597db27347527907b0))
* metric flusher and provider wiring (2/2) ref ICP-5378 ([#15](https://github.com/Dynatrace/openfeature-provider-java/issues/15)) ([3b4cde0](https://github.com/Dynatrace/openfeature-provider-java/commit/3b4cde031bbcf81a3dde7149f4132470bae6d038))

## [0.4.0-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.3.0-alpha...v0.4.0-alpha) (2026-05-26)


### ⚠ BREAKING CHANGES

* accept SDK key as first constructor argument ([#13](https://github.com/Dynatrace/openfeature-provider-java/issues/13))

### ✨ New Features

* accept SDK key as first constructor argument ([#13](https://github.com/Dynatrace/openfeature-provider-java/issues/13)) ([65f7e18](https://github.com/Dynatrace/openfeature-provider-java/commit/65f7e18a534d8e33ca04f340ad691ad195919165))

## [0.3.0-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.2.0-alpha...v0.3.0-alpha) (2026-05-20)


### ✨ New Features

* fixed-rate polling with in-flight retry cancellation ([#10](https://github.com/Dynatrace/openfeature-provider-java/issues/10)) ([8cd77e7](https://github.com/Dynatrace/openfeature-provider-java/commit/8cd77e7ce7c2c1f4b447feabbe10d91d79d0bd1d))

## [0.2.0-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.1.0-alpha...v0.2.0-alpha) (2026-05-13)


### ✨ New Features

* update SDK key format, ref: ICP-4977 ([#9](https://github.com/Dynatrace/openfeature-provider-java/issues/9)) ([e37fce5](https://github.com/Dynatrace/openfeature-provider-java/commit/e37fce5d9a77ab2b02b80fb1523525b90a902769))


### 🧹 Chore

* lint single-commit PR messages ([#7](https://github.com/Dynatrace/openfeature-provider-java/issues/7)) ([7b0e525](https://github.com/Dynatrace/openfeature-provider-java/commit/7b0e5258518362d931a01b1af6211647057c643f))

## [0.1.0-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.0.2-alpha...v0.1.0-alpha) (2026-05-12)


### ✨ New Features

* initial provider ([#2](https://github.com/Dynatrace/openfeature-provider-java/issues/2)) ([e3abf06](https://github.com/Dynatrace/openfeature-provider-java/commit/e3abf06cd3bfc22313f52447ff55aded9503e20b))
* retries, polling, state machine, ref: ICP: 3353 ([#4](https://github.com/Dynatrace/openfeature-provider-java/issues/4)) ([dbcce75](https://github.com/Dynatrace/openfeature-provider-java/commit/dbcce75f829cbb1cc144029ccd11a3bcef28e113))

## [0.0.2-alpha](https://github.com/Dynatrace/openfeature-provider-java/compare/v0.0.1-alpha...v0.0.2-alpha) (2026-04-16)


### 🧹 Chore

* signing and publishing ([d876687](https://github.com/Dynatrace/openfeature-provider-java/commit/d8766877d1d67aa0658209646958c98104f4f885))
