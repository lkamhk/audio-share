#include "pch.h"

#include "update_contract.hpp"
#include "util.hpp"

#include <afxinet.h>
#include <nlohmann/json.hpp>
#include <wil/resource.h>

#include <memory>
#include <stdexcept>

namespace audio_share::updater {
namespace {

std::wstring utf8_to_wide(const std::string& value)
{
    if (value.empty()) {
        return {};
    }

    const auto length = MultiByteToWideChar(
        CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0
    );
    if (length <= 0) {
        throw std::runtime_error("The update response contains invalid UTF-8.");
    }

    std::wstring result(static_cast<std::size_t>(length), L'\0');
    if (MultiByteToWideChar(
            CP_UTF8,
            MB_ERR_INVALID_CHARS,
            value.data(),
            static_cast<int>(value.size()),
            result.data(),
            length
        ) != length) {
        throw std::runtime_error("Failed to decode the update response.");
    }
    return result;
}

std::string wide_to_utf8(const std::wstring& value)
{
    if (value.empty()) {
        return {};
    }

    const auto length = WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr
    );
    if (length <= 0) {
        throw std::runtime_error("Failed to encode the current version.");
    }

    std::string result(static_cast<std::size_t>(length), '\0');
    if (WideCharToMultiByte(
            CP_UTF8,
            WC_ERR_INVALID_CHARS,
            value.data(),
            static_cast<int>(value.size()),
            result.data(),
            length,
            nullptr,
            nullptr
        ) != length) {
        throw std::runtime_error("Failed to encode the current version.");
    }
    return result;
}

std::string require_json_string(const nlohmann::json& value, const char* field)
{
    if (!value.contains(field) || !value[field].is_string()) {
        throw std::runtime_error(std::string("The update response is missing '") + field + "'.");
    }
    const auto result = value[field].get<std::string>();
    if (result.empty()) {
        throw std::runtime_error(std::string("The update response contains an empty '") + field + "'.");
    }
    return result;
}

} // namespace

update_check_result check_for_update(const std::wstring& current_version)
{
    try {
        CInternetSession session(
            L"Audio Share Server Updater",
            INTERNET_NO_CALLBACK,
            INTERNET_OPEN_TYPE_DIRECT,
            nullptr,
            nullptr,
            INTERNET_FLAG_DONT_CACHE
        );
        session.SetOption(INTERNET_OPTION_CONNECT_TIMEOUT, 10'000);
        session.SetOption(INTERNET_OPTION_SEND_TIMEOUT, 10'000);
        session.SetOption(INTERNET_OPTION_RECEIVE_TIMEOUT, 15'000);

        const auto request_url = build_manifest_url(current_version);
        std::unique_ptr<CStdioFile> internet_file(session.OpenURL(
            request_url.c_str(),
            INTERNET_NO_CALLBACK,
            INTERNET_FLAG_TRANSFER_BINARY | INTERNET_FLAG_RELOAD | INTERNET_FLAG_SECURE
        ));
        auto* http_file = dynamic_cast<CHttpFile*>(internet_file.get());
        if (!http_file) {
            throw std::runtime_error("The update service did not return an HTTP response.");
        }
        auto cleanup = wil::scope_exit([&] { http_file->Close(); });

        DWORD status_code = 0;
        if (!http_file->QueryInfoStatusCode(status_code)) {
            throw std::runtime_error("Failed to read the update service status code.");
        }
        if (status_code == HTTP_STATUS_NO_CONTENT) {
            return { update_check_status::no_update, std::nullopt, {} };
        }

        std::string response;
        char buffer[4096];
        while (const auto bytes_read = http_file->Read(buffer, sizeof(buffer))) {
            response.append(buffer, bytes_read);
            if (response.size() > 1024 * 1024) {
                throw std::runtime_error("The update response exceeds the 1 MiB limit.");
            }
        }
        if (status_code != HTTP_STATUS_OK) {
            throw std::runtime_error(
                "The update service returned HTTP " + std::to_string(status_code) + "."
            );
        }

        const auto document = nlohmann::json::parse(response);
        const auto version_utf8 = require_json_string(document, "version");
        const auto url_utf8 = require_json_string(document, "url");
        const auto signature = require_json_string(document, "signature");
        const auto notes = document.value("notes", std::string{});
        const auto published_at = document.value("pub_date", std::string{});

        if (!url_utf8.starts_with("https://")) {
            throw std::runtime_error("The update download URL does not use HTTPS.");
        }
        if (!util::is_newer_version(version_utf8, wide_to_utf8(current_version))) {
            return { update_check_status::no_update, std::nullopt, {} };
        }

        update_manifest manifest{
            utf8_to_wide(version_utf8),
            utf8_to_wide(url_utf8),
            signature,
            utf8_to_wide(notes),
            utf8_to_wide(published_at),
        };
        return { update_check_status::update_available, std::move(manifest), {} };
    }
    catch (const CInternetException* exception) {
        wchar_t message[512]{};
        exception->GetErrorMessage(message, static_cast<UINT>(std::size(message)));
        const_cast<CInternetException*>(exception)->Delete();
        return { update_check_status::error, std::nullopt, message };
    }
    catch (const std::exception& exception) {
        try {
            return { update_check_status::error, std::nullopt, utf8_to_wide(exception.what()) };
        }
        catch (...) {
            return { update_check_status::error, std::nullopt, L"Unknown update check error." };
        }
    }
}

} // namespace audio_share::updater
