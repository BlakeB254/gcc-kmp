fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## iOS

### ios create_cert

```sh
[bundle exec] fastlane ios create_cert
```

Create Apple Distribution certificate if needed

### ios release

```sh
[bundle exec] fastlane ios release
```

Build and upload to App Store Connect

### ios distribute

```sh
[bundle exec] fastlane ios distribute
```

Distribute an existing build to TestFlight groups

### ios submit

```sh
[bundle exec] fastlane ios submit
```

Submit latest build to App Store Review

### ios screenshots

```sh
[bundle exec] fastlane ios screenshots
```

Push screenshots + metadata only (no binary, no review submit)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
