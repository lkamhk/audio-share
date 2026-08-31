#pragma once

#include <optional>
#include <string>

namespace audio_share::updater {

inline constexpr wchar_t endpoint[] =
    L"https://nfqkislweudltvckonog.supabase.co/functions/v1/audio-share-update";
inline constexpr wchar_t channel[] = L"audio-share-server-stable";
inline constexpr wchar_t target[] = L"windows";
inline constexpr wchar_t architecture[] = L"x86_64";
inline constexpr wchar_t updater_executable_name[] = L"AudioShareUpdater.exe";
inline constexpr wchar_t server_executable_name[] = L"AudioShareServer.exe";
inline constexpr wchar_t updater_version[] = L"0.4.8";
inline constexpr wchar_t updater_title[] = L"Audio Share Updater v0.4.8";

struct update_manifest {
    std::wstring version;
    std::wstring download_url;
    std::string signature;
    std::wstring notes;
    std::wstring published_at;
};

enum class update_check_status {
    no_update,
    update_available,
    error,
};

struct update_check_result {
    update_check_status status{ update_check_status::error };
    std::optional<update_manifest> manifest;
    std::wstring error_message;
};

inline std::wstring build_manifest_url(const std::wstring& current_version)
{
    return std::wstring(endpoint) + L"?channel=" + channel + L"&target=" + target + L"&arch=" +
        architecture + L"&current_version=" + current_version;
}

update_check_result check_for_update(const std::wstring& current_version);

} // namespace audio_share::updater
