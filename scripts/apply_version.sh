pushd $(dirname $0)/.. &>/dev/null

version_name=$(bash ./scripts/get_version.sh -n)
version_code=$(bash ./scripts/get_version.sh -c)
file_version0=$version_name.0
file_version1=$(echo $file_version0 | sed -En 's|\.|,|gp')
product_version=$file_version1

echo VERSION: \
    version_name=$version_name \
    version_code=$version_code

printf '%s\n' "$version_name" > .ver

sed -Ebi "s|versionName\s*=\s*\"[^\"]*\"|versionName = \"$version_name\"|g" android-app/app/build.gradle.kts
sed -Ebi "s|versionCode\s*=\s*[0-9]*|versionCode = $version_code|g" android-app/app/build.gradle.kts
sed -Ebi "s|Audio Share v[0-9.]*|Audio Share v$version_name|g" android-app/app/src/main/res/values/values.xml
sed -Ebi "s|Audio Share v[0-9.]*|Audio Share v$version_name|g" android-app/app/src/main/java/io/github/mkckr0/audio_share_app/ui/screen/SettingsScreen.kt

sed -Ebi "s|\tVERSION\s+[0-9.]*|\tVERSION $version_name|g" server-core/CMakeLists.txt

sed -Ebi "s|Audio Share Server v[0-9.]*|Audio Share Server v$version_name|g" server-mfc/audio-share-server/AudioShareServer.cpp

# sed -Ebi "s|Audio Share Server, Version [^\"]*|Audio Share Server, Version $version_name|g" server-mfc/audio-share-server/AudioShareServer.rc
sed -Ebi "s|FILEVERSION [0-9,]*|FILEVERSION $file_version1|g" server-mfc/audio-share-server/AudioShareServer.rc
sed -Ebi "s|PRODUCTVERSION [0-9,]*|PRODUCTVERSION $product_version|g" server-mfc/audio-share-server/AudioShareServer.rc
sed -Ebi "s|\"FileVersion\", \"[^\"]*\"|\"FileVersion\", \"$version_name.0\"|g" server-mfc/audio-share-server/AudioShareServer.rc
sed -Ebi "s|\"ProductVersion\", \"[^\"]*\"|\"ProductVersion\", \"$version_name\"|g" server-mfc/audio-share-server/AudioShareServer.rc
sed -Ebi "s|CAPTION \"Audio Share Server v[0-9.]*\"|CAPTION \"Audio Share Server v$version_name\"|g" server-mfc/audio-share-server/AudioShareServer.rc

sed -Ebi "s|FILEVERSION [0-9,]*|FILEVERSION $file_version1|g" server-mfc/audio-share-server/i18n/AudioShareServer_zh-CN.rc
sed -Ebi "s|PRODUCTVERSION [0-9,]*|PRODUCTVERSION $product_version|g" server-mfc/audio-share-server/i18n/AudioShareServer_zh-CN.rc
sed -Ebi "s|\"FileVersion\", \"[^\"]*\"|\"FileVersion\", \"$version_name.0\"|g" server-mfc/audio-share-server/i18n/AudioShareServer_zh-CN.rc
sed -Ebi "s|\"ProductVersion\", \"[^\"]*\"|\"ProductVersion\", \"$version_name\"|g" server-mfc/audio-share-server/i18n/AudioShareServer_zh-CN.rc
sed -Ebi "s|CAPTION \"Audio Share Server v[0-9.]*\"|CAPTION \"Audio Share Server v$version_name\"|g" server-mfc/audio-share-server/i18n/AudioShareServer_zh-CN.rc

sed -Ebi "s|updater_version\[\] = L\"[0-9.]*\"|updater_version[] = L\"$version_name\"|g" server-mfc/updater-common/update_contract.hpp
sed -Ebi "s|Audio Share Updater v[0-9.]*|Audio Share Updater v$version_name|g" server-mfc/updater-common/update_contract.hpp
sed -Ebi "s|FILEVERSION [0-9,]*|FILEVERSION $file_version1|g" server-mfc/audio-share-updater/AudioShareUpdater.rc
sed -Ebi "s|PRODUCTVERSION [0-9,]*|PRODUCTVERSION $product_version|g" server-mfc/audio-share-updater/AudioShareUpdater.rc
sed -Ebi "s|\"FileVersion\", \"[^\"]*\"|\"FileVersion\", \"$version_name.0\"|g" server-mfc/audio-share-updater/AudioShareUpdater.rc
sed -Ebi "s|\"ProductVersion\", \"[^\"]*\"|\"ProductVersion\", \"$version_name\"|g" server-mfc/audio-share-updater/AudioShareUpdater.rc

popd &>/dev/null
