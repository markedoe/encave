// swift-tools-version: 6.0
import PackageDescription

// Platform-independent cellar model, storage and sync protocol. No UI code here,
// so it builds and tests on Linux as well as on Apple platforms.
// swift-crypto is Apple's open-source CryptoKit: on Apple platforms it uses
// CryptoKit itself, on Linux the same API backed by BoringSSL.
let package = Package(
    name: "CaveKit",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "CaveKit", targets: ["CaveKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"6.0.0"),
    ],
    targets: [
        .target(name: "CaveKit", dependencies: [.product(name: "Crypto", package: "swift-crypto")]),
        .testTarget(name: "CaveKitTests", dependencies: ["CaveKit"]),
    ]
)
