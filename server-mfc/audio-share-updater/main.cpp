#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "update_contract.hpp"
#include "update_security.hpp"

#include <windows.h>
#include <winhttp.h>
#include <shlobj.h>
#include <shellapi.h>
#include <nlohmann/json.hpp>

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace updater = audio_share::updater;

namespace {

constexpr std::uint64_t maximum_manifest_size = 1024ULL * 1024ULL;
constexpr std::uint64_t maximum_update_size = 256ULL * 1024ULL * 1024ULL;

struct internet_closer {
    void operator()(void* handle) const noexcept
    {
        if (handle) {
            WinHttpCloseHandle(static_cast<HINTERNET>(handle));
        }
    }
};

struct handle_closer {
    void operator()(void* handle) const noexcept
    {
        if (handle && handle != INVALID_HANDLE_VALUE) {
            CloseHandle(static_cast<HANDLE>(handle));
        }
    }
};

using unique_internet = std::unique_ptr<void, internet_closer>;
using unique_handle = std::unique_ptr<void, handle_closer>;

struct options {
    DWORD parent_pid{};
    std::wstring current_version;
};

struct http_response {
    DWORD status{};
    std::vector<std::uint8_t> body;
};

std::filesystem::path executable_directory()
{
    std::wstring buffer(32768, L'\0');
    const auto length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (length == 0 || length >= buffer.size()) {
        throw std::runtime_error("Failed to locate the update helper executable.");
    }
    buffer.resize(length);
    return std::filesystem::path(buffer).parent_path();
}

std::wstring quote_argument(const std::wstring& value)
{
    std::wstring result = L"\"";
    for (const auto ch : value) {
        if (ch == L'\"') {
            result += L"\\\"";
        }
        else {
            result += ch;
        }
    }
    result += L"\"";
    return result;
}

options parse_options(const int argc, wchar_t** argv)
{
    options result;
    for (int index = 1; index < argc; ++index) {
        const std::wstring argument = argv[index];
        if (argument == L"--pid" && index + 1 < argc) {
            const auto value = std::stoull(argv[++index]);
            if (value == 0 || value > std::numeric_limits<DWORD>::max()) {
                throw std::runtime_error("The parent process ID is invalid.");
            }
            result.parent_pid = static_cast<DWORD>(value);
        }
        else if (argument == L"--current-version" && index + 1 < argc) {
            result.current_version = argv[++index];
        }
        else {
            throw std::runtime_error("Unsupported update helper argument.");
        }
    }
    if (result.parent_pid == 0 || result.current_version.empty()) {
        throw std::runtime_error("The update helper requires --pid and --current-version.");
    }
    return result;
}

std::wstring utf8_to_wide(const std::string& value)
{
    if (value.empty()) {
        return {};
    }
    const auto length = MultiByteToWideChar(
        CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0
    );
    if (length <= 0) {
        throw std::runtime_error("The update manifest contains invalid UTF-8.");
    }
    std::wstring result(static_cast<std::size_t>(length), L'\0');
    if (MultiByteToWideChar(
            CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
            result.data(), length
        ) != length) {
        throw std::runtime_error("Failed to decode the update manifest.");
    }
    return result;
}

std::string wide_to_utf8(const std::wstring& value)
{
    if (value.empty()) {
        return {};
    }
    const auto length = WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
        nullptr, 0, nullptr, nullptr
    );
    if (length <= 0) {
        throw std::runtime_error("Failed to encode an updater validation error.");
    }
    std::string result(static_cast<std::size_t>(length), '\0');
    if (WideCharToMultiByte(
            CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
            result.data(), length, nullptr, nullptr
        ) != length) {
        throw std::runtime_error("Failed to encode an updater validation error.");
    }
    return result;
}

void log_line(const std::filesystem::path& log_file, const std::wstring& line) noexcept
{
    try {
        std::filesystem::create_directories(log_file.parent_path());
        std::wofstream stream(log_file, std::ios::app);
        if (stream) {
            const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
            std::tm local_time{};
            localtime_s(&local_time, &now);
            stream << std::put_time(&local_time, L"%Y-%m-%d %H:%M:%S") << L" " << line << L"\n";
        }
    }
    catch (...) {
    }
}

http_response http_get(const std::wstring& url, const std::uint64_t maximum_size)
{
    URL_COMPONENTSW components{};
    components.dwStructSize = sizeof(components);
    components.dwSchemeLength = static_cast<DWORD>(-1);
    components.dwHostNameLength = static_cast<DWORD>(-1);
    components.dwUrlPathLength = static_cast<DWORD>(-1);
    components.dwExtraInfoLength = static_cast<DWORD>(-1);
    if (!WinHttpCrackUrl(url.c_str(), static_cast<DWORD>(url.size()), 0, &components) ||
        components.nScheme != INTERNET_SCHEME_HTTPS) {
        throw std::runtime_error("The update URL must be a valid HTTPS URL.");
    }

    unique_internet session(WinHttpOpen(
        (std::wstring(L"Audio Share Updater/") + updater::updater_version).c_str(),
        WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0
    ));
    if (!session) {
        throw std::runtime_error("Failed to initialize WinHTTP.");
    }
    WinHttpSetTimeouts(session.get(), 10'000, 10'000, 15'000, 30'000);

    const std::wstring host(components.lpszHostName, components.dwHostNameLength);
    unique_internet connection(WinHttpConnect(session.get(), host.c_str(), components.nPort, 0));
    if (!connection) {
        throw std::runtime_error("Failed to connect to the update host.");
    }

    std::wstring path(components.lpszUrlPath, components.dwUrlPathLength);
    path.append(components.lpszExtraInfo, components.dwExtraInfoLength);
    unique_internet request(WinHttpOpenRequest(
        connection.get(), L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
        WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE
    ));
    if (!request) {
        throw std::runtime_error("Failed to create the update request.");
    }
    DWORD redirect_policy = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    WinHttpSetOption(request.get(), WINHTTP_OPTION_REDIRECT_POLICY, &redirect_policy, sizeof(redirect_policy));
    if (!WinHttpSendRequest(
            request.get(), WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0
        ) || !WinHttpReceiveResponse(request.get(), nullptr)) {
        throw std::runtime_error("The update HTTP request failed.");
    }

    DWORD status = 0;
    DWORD status_size = sizeof(status);
    if (!WinHttpQueryHeaders(
            request.get(), WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            WINHTTP_HEADER_NAME_BY_INDEX, &status, &status_size, WINHTTP_NO_HEADER_INDEX
        )) {
        throw std::runtime_error("Failed to read the update HTTP status.");
    }

    http_response response{ status, {} };
    while (true) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request.get(), &available)) {
            throw std::runtime_error("Failed while receiving the update response.");
        }
        if (available == 0) {
            break;
        }
        if (response.body.size() > maximum_size || available > maximum_size - response.body.size()) {
            throw std::runtime_error("The update response exceeds the allowed size.");
        }
        const auto old_size = response.body.size();
        response.body.resize(old_size + available);
        DWORD read = 0;
        if (!WinHttpReadData(request.get(), response.body.data() + old_size, available, &read)) {
            throw std::runtime_error("Failed while reading the update response.");
        }
        response.body.resize(old_size + read);
    }
    return response;
}

updater::update_manifest read_manifest(const std::wstring& current_version)
{
    const auto response = http_get(updater::build_manifest_url(current_version), maximum_manifest_size);
    if (response.status == HTTP_STATUS_NO_CONTENT) {
        return {};
    }
    if (response.status != HTTP_STATUS_OK) {
        throw std::runtime_error("The update service did not return a usable manifest.");
    }
    const std::string json_text(response.body.begin(), response.body.end());
    const auto json = nlohmann::json::parse(json_text);
    const auto version = json.at("version").get<std::string>();
    const auto url = json.at("url").get<std::string>();
    const auto signature = json.at("signature").get<std::string>();
    if (!url.starts_with("https://") || signature.empty()) {
        throw std::runtime_error("The update manifest is missing a secure URL or signature.");
    }
    return {
        utf8_to_wide(version), utf8_to_wide(url), signature,
        utf8_to_wide(json.value("notes", std::string{})),
        utf8_to_wide(json.value("pub_date", std::string{})),
    };
}

std::filesystem::path create_staging_directory()
{
    PWSTR raw_path = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &raw_path))) {
        throw std::runtime_error("Failed to locate LocalAppData.");
    }
    std::unique_ptr<wchar_t, decltype(&CoTaskMemFree)> local_app_data(raw_path, CoTaskMemFree);
    const auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
    const auto directory = std::filesystem::path(local_app_data.get()) / L"AudioShare" / L"updates" /
        (std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(stamp));
    std::filesystem::create_directories(directory);
    return directory;
}

void write_file(const std::filesystem::path& path, const std::vector<std::uint8_t>& data)
{
    std::ofstream stream(path, std::ios::binary | std::ios::trunc);
    if (!stream) {
        throw std::runtime_error("Failed to create the downloaded update file.");
    }
    stream.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    if (!stream) {
        throw std::runtime_error("Failed to write the complete update file.");
    }
}

void wait_for_parent(const DWORD parent_pid)
{
    unique_handle process(OpenProcess(SYNCHRONIZE, FALSE, parent_pid));
    if (!process) {
        if (GetLastError() == ERROR_INVALID_PARAMETER) {
            return;
        }
        throw std::runtime_error("Failed to wait for AudioShareServer.exe to exit.");
    }
    if (WaitForSingleObject(process.get(), 60'000) != WAIT_OBJECT_0) {
        throw std::runtime_error("AudioShareServer.exe did not exit within 60 seconds.");
    }
}

void run_tar_extract(const std::filesystem::path& archive, const std::filesystem::path& destination)
{
    wchar_t system_directory[MAX_PATH]{};
    if (!GetSystemDirectoryW(system_directory, static_cast<UINT>(std::size(system_directory)))) {
        throw std::runtime_error("Failed to locate the Windows system directory.");
    }
    const auto tar_path = std::filesystem::path(system_directory) / L"tar.exe";
    if (!std::filesystem::is_regular_file(tar_path)) {
        throw std::runtime_error("Windows tar.exe is required to extract the signed update ZIP.");
    }

    auto command = quote_argument(tar_path.wstring()) + L" -xf " + quote_argument(archive.wstring()) +
        L" -C " + quote_argument(destination.wstring());
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process_info{};
    if (!CreateProcessW(
            tar_path.c_str(), command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
            nullptr, destination.c_str(), &startup, &process_info
        )) {
        throw std::runtime_error("Failed to start the Windows ZIP extractor.");
    }
    unique_handle process(process_info.hProcess);
    unique_handle thread(process_info.hThread);
    if (WaitForSingleObject(process.get(), 60'000) != WAIT_OBJECT_0) {
        TerminateProcess(process.get(), 1);
        throw std::runtime_error("The Windows ZIP extractor timed out.");
    }
    DWORD exit_code = 1;
    if (!GetExitCodeProcess(process.get(), &exit_code) || exit_code != 0) {
        throw std::runtime_error("The Windows ZIP extractor rejected the update ZIP.");
    }
}

std::filesystem::path validate_extracted_payload(const std::filesystem::path& directory)
{
    std::optional<std::filesystem::path> payload;
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        const auto attributes = GetFileAttributesW(entry.path().c_str());
        if (payload || !entry.is_regular_file() || entry.path().filename() != updater::server_executable_name ||
            attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw std::runtime_error("The extracted update contains an unexpected file or directory.");
        }
        payload = entry.path();
    }
    if (!payload) {
        throw std::runtime_error("AudioShareServer.exe was not extracted from the update ZIP.");
    }
    return *payload;
}

void launch_server(const std::filesystem::path& server)
{
    auto command = quote_argument(server.wstring());
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process_info{};
    if (!CreateProcessW(
            server.c_str(), command.data(), nullptr, nullptr, FALSE, 0, nullptr,
            server.parent_path().c_str(), &startup, &process_info
        )) {
        throw std::runtime_error("Failed to restart AudioShareServer.exe.");
    }
    CloseHandle(process_info.hThread);
    CloseHandle(process_info.hProcess);
}

void replace_server(const std::filesystem::path& target, const std::filesystem::path& replacement)
{
    const std::filesystem::path backup(target.wstring() + L".bak");
    std::error_code ignored;
    std::filesystem::remove(backup, ignored);
    if (!MoveFileExW(target.c_str(), backup.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        throw std::runtime_error("Failed to back up the installed AudioShareServer.exe.");
    }
    if (!MoveFileExW(replacement.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        MoveFileExW(backup.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
        throw std::runtime_error("Failed to install the update; the previous version was restored.");
    }
    try {
        launch_server(target);
    }
    catch (...) {
        std::filesystem::remove(target, ignored);
        MoveFileExW(backup.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
        throw;
    }
}

} // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int)
{
    const auto base_directory = executable_directory();
    const auto target = base_directory / updater::server_executable_name;
    const auto log_file = base_directory / L"logs" / L"updater.log";
    std::optional<std::filesystem::path> staging;
    DWORD parent_pid = 0;

    try {
        int argc = 0;
        auto* raw_arguments = CommandLineToArgvW(GetCommandLineW(), &argc);
        if (!raw_arguments) {
            throw std::runtime_error("Failed to parse the update helper command line.");
        }
        std::unique_ptr<wchar_t*, decltype(&LocalFree)> arguments(raw_arguments, LocalFree);
        const auto parsed = parse_options(argc, raw_arguments);
        parent_pid = parsed.parent_pid;

        log_line(log_file, L"Update check started for version " + parsed.current_version + L".");
        const auto manifest = read_manifest(parsed.current_version);
        if (manifest.version.empty()) {
            log_line(log_file, L"No newer update is available.");
            wait_for_parent(parsed.parent_pid);
            launch_server(target);
            return 0;
        }

        staging = create_staging_directory();
        const auto archive = *staging / L"update.zip";
        const auto extract_directory = *staging / L"payload";
        std::filesystem::create_directories(extract_directory);

        const auto download = http_get(manifest.download_url, maximum_update_size);
        if (download.status != HTTP_STATUS_OK || download.body.empty()) {
            throw std::runtime_error("Dropbox did not return the update ZIP.");
        }
        write_file(archive, download.body);

        std::wstring validation_error;
        if (!updater::verify_update_signature(archive, manifest.signature, validation_error)) {
            throw std::runtime_error(
                "Update signature verification failed: " + wide_to_utf8(validation_error)
            );
        }
        if (!updater::validate_update_zip(archive, validation_error)) {
            throw std::runtime_error(
                "Update ZIP validation failed: " + wide_to_utf8(validation_error)
            );
        }

        run_tar_extract(archive, extract_directory);
        const auto replacement = validate_extracted_payload(extract_directory);
        wait_for_parent(parsed.parent_pid);
        replace_server(target, replacement);
        log_line(log_file, L"Updated successfully to version " + manifest.version + L".");
        std::filesystem::remove_all(*staging);
        return 0;
    }
    catch (const std::exception& exception) {
        std::wstring message;
        try {
            message = utf8_to_wide(exception.what());
        }
        catch (...) {
            message = L"Unknown update error.";
        }
        log_line(log_file, L"Update failed: " + message);
        if (staging) {
            std::error_code ignored;
            std::filesystem::remove_all(*staging, ignored);
        }
        if (parent_pid != 0) {
            try {
                wait_for_parent(parent_pid);
            }
            catch (...) {
            }
        }
        if (std::filesystem::is_regular_file(target)) {
            try {
                launch_server(target);
            }
            catch (...) {
            }
        }
        const auto title = std::wstring(updater::updater_title) + L" - Update Failed";
        MessageBoxW(nullptr, message.c_str(), title.c_str(), MB_OK | MB_ICONERROR);
        return 1;
    }
}
