// swift-tools-version: 6.0
import PackageDescription

// Platform-independent cellar model and storage. No UI code here, so it
// builds and tests on Linux as well as on Apple platforms.
let package = Package(
    name: "CaveKit",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "CaveKit", targets: ["CaveKit"]),
    ],
    targets: [
        .target(name: "CaveKit"),
        .testTarget(name: "CaveKitTests", dependencies: ["CaveKit"]),
    ]
)
