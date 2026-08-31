#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "update_security.hpp"
#include "update_public_key.hpp"

#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <vector>

namespace audio_share::updater {
namespace {

constexpr std::uint64_t maximum_update_size = 256ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t local_file_header_signature = 0x04034b50U;
constexpr std::uint32_t central_directory_header_signature = 0x02014b50U;
constexpr std::uint32_t end_of_central_directory_signature = 0x06054b50U;

struct algorithm_closer {
    void operator()(void* handle) const noexcept
    {
        if (handle) {
            BCryptCloseAlgorithmProvider(static_cast<BCRYPT_ALG_HANDLE>(handle), 0);
        }
    }
};

struct hash_closer {
    void operator()(void* handle) const noexcept
    {
        if (handle) {
            BCryptDestroyHash(static_cast<BCRYPT_HASH_HANDLE>(handle));
        }
    }
};

struct key_closer {
    void operator()(void* handle) const noexcept
    {
        if (handle) {
            BCryptDestroyKey(static_cast<BCRYPT_KEY_HANDLE>(handle));
        }
    }
};

using unique_algorithm = std::unique_ptr<void, algorithm_closer>;
using unique_hash = std::unique_ptr<void, hash_closer>;
using unique_key = std::unique_ptr<void, key_closer>;

bool nt_success(const NTSTATUS status)
{
    return status >= 0;
}

bool decode_base64(const std::string& text, std::vector<std::uint8_t>& output)
{
    DWORD required = 0;
    if (!CryptStringToBinaryA(
            text.c_str(), static_cast<DWORD>(text.size()), CRYPT_STRING_BASE64_ANY,
            nullptr, &required, nullptr, nullptr
        )) {
        return false;
    }
    output.resize(required);
    if (!CryptStringToBinaryA(
            text.c_str(), static_cast<DWORD>(text.size()), CRYPT_STRING_BASE64_ANY,
            output.data(), &required, nullptr, nullptr
        )) {
        return false;
    }
    output.resize(required);
    return true;
}

bool sha256_file(const std::filesystem::path& file, std::array<std::uint8_t, 32>& digest, std::wstring& error)
{
    BCRYPT_ALG_HANDLE algorithm_handle = nullptr;
    if (!nt_success(BCryptOpenAlgorithmProvider(&algorithm_handle, BCRYPT_SHA256_ALGORITHM, nullptr, 0))) {
        error = L"Failed to initialize SHA-256.";
        return false;
    }
    unique_algorithm algorithm(algorithm_handle);

    DWORD object_size = 0;
    DWORD bytes_written = 0;
    if (!nt_success(BCryptGetProperty(
            algorithm_handle, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&object_size),
            sizeof(object_size), &bytes_written, 0
        ))) {
        error = L"Failed to query the SHA-256 object size.";
        return false;
    }

    std::vector<std::uint8_t> hash_object(object_size);
    BCRYPT_HASH_HANDLE hash_handle = nullptr;
    if (!nt_success(BCryptCreateHash(
            algorithm_handle, &hash_handle, hash_object.data(), static_cast<ULONG>(hash_object.size()),
            nullptr, 0, 0
        ))) {
        error = L"Failed to create the SHA-256 hash.";
        return false;
    }
    unique_hash hash(hash_handle);

    std::ifstream stream(file, std::ios::binary);
    if (!stream) {
        error = L"Failed to open the downloaded update for hashing.";
        return false;
    }
    std::array<char, 64 * 1024> buffer{};
    while (stream) {
        stream.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        const auto count = stream.gcount();
        if (count > 0 && !nt_success(BCryptHashData(
                hash_handle, reinterpret_cast<PUCHAR>(buffer.data()), static_cast<ULONG>(count), 0
            ))) {
            error = L"Failed while hashing the downloaded update.";
            return false;
        }
    }
    if (!stream.eof()) {
        error = L"Failed while reading the downloaded update.";
        return false;
    }
    if (!nt_success(BCryptFinishHash(hash_handle, digest.data(), static_cast<ULONG>(digest.size()), 0))) {
        error = L"Failed to finish the SHA-256 hash.";
        return false;
    }
    return true;
}

std::uint16_t read_u16(const std::vector<std::uint8_t>& data, const std::size_t offset)
{
    if (offset > data.size() || data.size() - offset < 2) {
        throw std::out_of_range("ZIP field is outside the file.");
    }
    return static_cast<std::uint16_t>(data[offset]) |
        (static_cast<std::uint16_t>(data[offset + 1]) << 8U);
}

std::uint32_t read_u32(const std::vector<std::uint8_t>& data, const std::size_t offset)
{
    if (offset > data.size() || data.size() - offset < 4) {
        throw std::out_of_range("ZIP field is outside the file.");
    }
    return static_cast<std::uint32_t>(data[offset]) |
        (static_cast<std::uint32_t>(data[offset + 1]) << 8U) |
        (static_cast<std::uint32_t>(data[offset + 2]) << 16U) |
        (static_cast<std::uint32_t>(data[offset + 3]) << 24U);
}

std::string read_name(
    const std::vector<std::uint8_t>& data, const std::size_t offset, const std::size_t length
)
{
    if (offset > data.size() || length > data.size() - offset) {
        throw std::out_of_range("ZIP file name is outside the file.");
    }
    return std::string(
        reinterpret_cast<const char*>(data.data() + offset),
        reinterpret_cast<const char*>(data.data() + offset + length)
    );
}

} // namespace

bool verify_update_signature(
    const std::filesystem::path& file,
    const std::string& base64_signature,
    std::wstring& error
)
{
    if (!public_key_configured) {
        error = L"The updater public key has not been configured.";
        return false;
    }

    std::vector<std::uint8_t> signature;
    if (!decode_base64(base64_signature, signature) || signature.size() != 64) {
        error = L"The update signature is not a valid 64-byte ECDSA P-256 signature.";
        return false;
    }

    std::array<std::uint8_t, 32> digest{};
    if (!sha256_file(file, digest, error)) {
        return false;
    }

    BCRYPT_ALG_HANDLE algorithm_handle = nullptr;
    if (!nt_success(BCryptOpenAlgorithmProvider(&algorithm_handle, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0))) {
        error = L"Failed to initialize ECDSA P-256.";
        return false;
    }
    unique_algorithm algorithm(algorithm_handle);

    std::vector<std::uint8_t> public_blob(sizeof(BCRYPT_ECCKEY_BLOB) + public_key_x.size() + public_key_y.size());
    auto* header = reinterpret_cast<BCRYPT_ECCKEY_BLOB*>(public_blob.data());
    header->dwMagic = BCRYPT_ECDSA_PUBLIC_P256_MAGIC;
    header->cbKey = static_cast<ULONG>(public_key_x.size());
    std::copy(public_key_x.begin(), public_key_x.end(), public_blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB));
    std::copy(
        public_key_y.begin(), public_key_y.end(),
        public_blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB) + public_key_x.size()
    );

    BCRYPT_KEY_HANDLE key_handle = nullptr;
    if (!nt_success(BCryptImportKeyPair(
            algorithm_handle, nullptr, BCRYPT_ECCPUBLIC_BLOB, &key_handle,
            public_blob.data(), static_cast<ULONG>(public_blob.size()), 0
        ))) {
        error = L"Failed to import the updater public key.";
        return false;
    }
    unique_key key(key_handle);

    if (!nt_success(BCryptVerifySignature(
            key_handle, nullptr, digest.data(), static_cast<ULONG>(digest.size()),
            signature.data(), static_cast<ULONG>(signature.size()), 0
        ))) {
        error = L"The update signature is invalid.";
        return false;
    }
    return true;
}

bool validate_update_zip(const std::filesystem::path& file, std::wstring& error)
{
    try {
        const auto file_size = std::filesystem::file_size(file);
        if (file_size < 22 || file_size > maximum_update_size) {
            error = L"The update ZIP size is outside the allowed range.";
            return false;
        }

        std::ifstream stream(file, std::ios::binary);
        if (!stream) {
            error = L"Failed to open the update ZIP.";
            return false;
        }
        std::vector<std::uint8_t> data(static_cast<std::size_t>(file_size));
        stream.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()));
        if (static_cast<std::size_t>(stream.gcount()) != data.size()) {
            error = L"Failed to read the complete update ZIP.";
            return false;
        }

        const auto search_start = data.size() > 65'557 ? data.size() - 65'557 : 0;
        std::size_t eocd = std::numeric_limits<std::size_t>::max();
        for (auto position = data.size() - 22;; --position) {
            if (read_u32(data, position) == end_of_central_directory_signature) {
                const auto comment_length = read_u16(data, position + 20);
                if (position + 22 + comment_length == data.size()) {
                    eocd = position;
                    break;
                }
            }
            if (position == search_start) {
                break;
            }
        }
        if (eocd == std::numeric_limits<std::size_t>::max()) {
            error = L"The update file does not contain a valid ZIP directory.";
            return false;
        }
        if (read_u16(data, eocd + 4) != 0 || read_u16(data, eocd + 6) != 0 ||
            read_u16(data, eocd + 8) != 1 || read_u16(data, eocd + 10) != 1) {
            error = L"The update ZIP must contain exactly one non-spanned file.";
            return false;
        }

        const auto central_size = read_u32(data, eocd + 12);
        const auto central_offset = read_u32(data, eocd + 16);
        if (central_offset > eocd || central_size > eocd - central_offset ||
            static_cast<std::size_t>(central_offset) + central_size != eocd) {
            error = L"The update ZIP central directory is malformed.";
            return false;
        }
        if (read_u32(data, central_offset) != central_directory_header_signature) {
            error = L"The update ZIP central directory header is invalid.";
            return false;
        }

        const auto central_flags = read_u16(data, central_offset + 8);
        const auto compression_method = read_u16(data, central_offset + 10);
        const auto compressed_size = read_u32(data, central_offset + 20);
        const auto uncompressed_size = read_u32(data, central_offset + 24);
        const auto name_length = read_u16(data, central_offset + 28);
        const auto extra_length = read_u16(data, central_offset + 30);
        const auto comment_length = read_u16(data, central_offset + 32);
        const auto local_offset = read_u32(data, central_offset + 42);
        const auto expected_name = std::string("AudioShareServer.exe");
        const auto central_name = read_name(data, central_offset + 46, name_length);
        const auto central_entry_size = 46ULL + name_length + extra_length + comment_length;

        if ((central_flags & 0x0001U) != 0 || (compression_method != 0 && compression_method != 8)) {
            error = L"The update ZIP uses unsupported encryption or compression.";
            return false;
        }
        if (central_name != expected_name || central_entry_size != central_size) {
            error = L"The update ZIP must contain only AudioShareServer.exe at its root.";
            return false;
        }
        if (compressed_size == 0 || uncompressed_size == 0 || uncompressed_size > 128U * 1024U * 1024U) {
            error = L"The update executable size is invalid.";
            return false;
        }
        if (local_offset != 0 || read_u32(data, local_offset) != local_file_header_signature) {
            error = L"The update ZIP local file header is invalid.";
            return false;
        }

        const auto local_flags = read_u16(data, local_offset + 6);
        const auto local_method = read_u16(data, local_offset + 8);
        const auto local_name_length = read_u16(data, local_offset + 26);
        const auto local_extra_length = read_u16(data, local_offset + 28);
        const auto local_name = read_name(data, local_offset + 30, local_name_length);
        const auto payload_offset = static_cast<std::uint64_t>(local_offset) + 30ULL +
            local_name_length + local_extra_length;
        if ((local_flags & 0x0001U) != 0 || local_method != compression_method || local_name != expected_name) {
            error = L"The update ZIP local entry does not match the signed directory.";
            return false;
        }
        if (payload_offset > central_offset || compressed_size != central_offset - payload_offset) {
            error = L"The update ZIP payload is outside the valid archive range.";
            return false;
        }
        return true;
    }
    catch (const std::exception&) {
        error = L"The update ZIP is malformed.";
        return false;
    }
}

} // namespace audio_share::updater
