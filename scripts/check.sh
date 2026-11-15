#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

plutil -lint ios/AgentTeamsMobile.xcodeproj/project.pbxproj ios/App/Info.plist ios/App/Info.Debug.plist
test_args=(--package-path ios --disable-xctest)
developer_dir="$(xcode-select -p)"
if [[ "$developer_dir" == */CommandLineTools ]]; then
    # CLT ships Swift Testing as a framework but omits its Foundation overlay module.
    frameworks="$developer_dir/Library/Developer/Frameworks"
    test_args+=(-Xswiftc -F -Xswiftc "$frameworks"
                -Xswiftc -Xfrontend -Xswiftc -disable-cross-import-overlays
                -Xlinker -F -Xlinker "$frameworks"
                -Xlinker -rpath -Xlinker "$frameworks")
fi
swift test "${test_args[@]}"

if xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
    xcodebuild -project ios/AgentTeamsMobile.xcodeproj \
        -scheme AgentTeamsMobile -configuration Debug \
        -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO build
else
    echo 'SKIP: iOS Simulator SDK unavailable; no iOS App build was performed.'
fi
