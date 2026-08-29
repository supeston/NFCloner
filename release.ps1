$ErrorActionPreference = "Stop"

$version = "v1.2.7"
$apkPath = "app\build\outputs\apk\release\app-release.apk"
$desktopApk = [System.IO.Path]::Combine([Environment]::GetFolderPath("Desktop"), "NFCloner.apk")

# Copy to desktop
Copy-Item -Path $apkPath -Destination $desktopApk -Force
Write-Host "Copied APK to desktop."

# Commit changes
git add .
git commit -m "feat: $version - exact transport card parsing, dump support, UI fixes, hard-clean build"
git tag $version
git push origin main --tags

# Create Github Release
gh release create $version $apkPath --title "NFCloner $version" --notes "Fixes and improvements"

Write-Host "Release created!"
