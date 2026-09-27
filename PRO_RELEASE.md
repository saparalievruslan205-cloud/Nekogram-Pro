# Nekogram Pro releases

The Pro app is the `debug` variant with application ID `tw.nekomimi.nekogram.beta`. Keep that ID and the existing signing certificate to update installed copies without erasing app data. Never commit the keystore, `local.properties`, API credentials, or `google-services.json`.

`PRO_REVISION` in `gradle.properties` is a fork revision within the upstream `APP_VERSION_CODE`. The Pro APK version code is `APP_VERSION_CODE * 1000 + PRO_REVISION`; it must strictly increase for every published update. The arm64 artifact is named `Nekogram-Pro-<APP_VERSION_NAME>-<versionCode>-arm64-v8a.apk`.

Publish an ordinary (non-prerelease) GitHub Release from the corresponding commit with tag `pro-<versionCode>-<APP_VERSION_NAME>`, and attach the signed APK. The in-app checker reads this fork's latest release, selects an APK for the device ABI, and offers its direct download link. Android may require the user to approve installation. A source-only release or a release without the matching APK is ignored.

Before publication, verify the package ID, monotonically increasing version code, signing certificate, APK checksum, successful installation over the previous Pro build, and launch. Keep the original `tw.nekomimi.nekogram` app untouched.
