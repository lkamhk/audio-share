#pragma once

#include <filesystem>
#include <string>

namespace audio_share::updater {

bool verify_update_signature(
    const std::filesystem::path& file,
    const std::string& base64_signature,
    std::wstring& error
);

bool validate_update_zip(const std::filesystem::path& file, std::wstring& error);

} // namespace audio_share::updater
