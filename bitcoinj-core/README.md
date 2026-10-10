# bitcoinj-core 0.17.1 (source module)

This module contains the `core/src/main` source, resources, and protocol definitions from the supplied bitcoinj 0.17.1 source archive. B-Lite compiles it as a local Gradle project instead of resolving `org.bitcoinj:bitcoinj-core:0.17.1` as a Maven artifact.

The Protobuf Java classes used by bitcoinj are committed under `src/main/java/org/bitcoinj/protobuf/` so this module does not need the Protobuf Gradle plugin or a `protoc` download during normal builds. They were generated from the included `.proto` definitions using `protoc 3.13.0` available in the build environment.

The code is licensed under Apache License 2.0; see `LICENSE` and source-file notices.

Note: This removes the bitcoinj-core artifact and Protobuf compiler/plugin downloads. It does not vendor all third-party libraries, the Android Gradle Plugin, AndroidX dependencies, or the Gradle distribution. A fully network-isolated build still requires those existing project dependencies and the Gradle distribution to be present in the local Gradle cache or an approved local repository.
