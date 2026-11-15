// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AgentTeams",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ATCore", targets: ["ATCore"]),
        .library(name: "ATUI", targets: ["ATUI"]),
    ],
    targets: [
        .target(name: "ATCore"),
        .target(name: "ATUI", dependencies: ["ATCore"]),
        .testTarget(name: "ATCoreTests", dependencies: ["ATCore"]),
    ]
)
