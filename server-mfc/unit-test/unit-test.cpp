#include "pch.h"
#include "CppUnitTest.h"
#include "../audio-share-server/util.hpp"
#include "../updater-common/update_security.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <vector>

using namespace Microsoft::VisualStudio::CppUnitTestFramework;

namespace unittest
{
    namespace
    {
        void append_u16(std::vector<std::uint8_t>& bytes, const std::uint16_t value)
        {
            bytes.push_back(static_cast<std::uint8_t>(value));
            bytes.push_back(static_cast<std::uint8_t>(value >> 8U));
        }

        void append_u32(std::vector<std::uint8_t>& bytes, const std::uint32_t value)
        {
            append_u16(bytes, static_cast<std::uint16_t>(value));
            append_u16(bytes, static_cast<std::uint16_t>(value >> 16U));
        }

        std::vector<std::uint8_t> make_stored_zip(const std::string& name, const bool hidden_bytes)
        {
            constexpr std::uint32_t payload_size = 1;
            std::vector<std::uint8_t> bytes;

            append_u32(bytes, 0x04034b50U);
            append_u16(bytes, 20);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u32(bytes, 0);
            append_u32(bytes, payload_size);
            append_u32(bytes, payload_size);
            append_u16(bytes, static_cast<std::uint16_t>(name.size()));
            append_u16(bytes, 0);
            bytes.insert(bytes.end(), name.begin(), name.end());
            bytes.push_back(0x42);
            if (hidden_bytes) {
                bytes.push_back(0x13);
                bytes.push_back(0x37);
            }

            const auto central_offset = static_cast<std::uint32_t>(bytes.size());
            append_u32(bytes, 0x02014b50U);
            append_u16(bytes, 20);
            append_u16(bytes, 20);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u32(bytes, 0);
            append_u32(bytes, payload_size);
            append_u32(bytes, payload_size);
            append_u16(bytes, static_cast<std::uint16_t>(name.size()));
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u32(bytes, 0);
            append_u32(bytes, 0);
            bytes.insert(bytes.end(), name.begin(), name.end());

            const auto central_size = static_cast<std::uint32_t>(bytes.size()) - central_offset;
            append_u32(bytes, 0x06054b50U);
            append_u16(bytes, 0);
            append_u16(bytes, 0);
            append_u16(bytes, 1);
            append_u16(bytes, 1);
            append_u32(bytes, central_size);
            append_u32(bytes, central_offset);
            append_u16(bytes, 0);
            return bytes;
        }

        class temporary_zip
        {
        public:
            explicit temporary_zip(const std::vector<std::uint8_t>& bytes)
            {
                const auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
                path = std::filesystem::temp_directory_path() /
                    (L"audio-share-updater-test-" + std::to_wstring(stamp) + L".zip");
                std::ofstream stream(path, std::ios::binary | std::ios::trunc);
                stream.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
                if (!stream) {
                    throw std::runtime_error("Failed to create the updater test ZIP.");
                }
            }

            ~temporary_zip()
            {
                std::error_code ignored;
                std::filesystem::remove(path, ignored);
            }

            std::filesystem::path path;
        };
    }

	TEST_CLASS(test_split_string)
	{
	public:
		
        TEST_METHOD(split_string_0) {
            Assert::IsTrue(std::vector<std::string>{"1", "2", "3"} == util::split_string("1.2.3", '.'));
        }

        TEST_METHOD(split_string_1) {
            Assert::IsTrue(std::vector<std::string>{"2", "3"} == util::split_string(".2.3", '.'));
        }

        TEST_METHOD(split_string_2) {
            Assert::IsTrue(std::vector<std::string>{} == util::split_string("", '.'));
        }

        TEST_METHOD(split_string_3) {
            Assert::IsTrue(std::vector<std::string>{} == util::split_string(".", '.'));
        }
	};

    TEST_CLASS(test_is_newer_version)
    {
    public:
        TEST_METHOD(is_newer_version_accepts_manifest_semver) {
            Assert::IsTrue(util::is_newer_version("0.4.8", "0.4.7"));
            Assert::IsTrue(util::is_newer_version("0.4.8", "v0.4.7"));
            Assert::IsFalse(util::is_newer_version("v0.4.7", "0.4.8"));
        }

        TEST_METHOD(is_newer_version_rejects_invalid_versions) {
            const std::array<std::string, 7> invalid_versions{
                "", "v", "3.2", "v3.2.", "1.2.3.4", "1.two.3", "1.2.42949672960"
            };
            for (const auto& invalid : invalid_versions) {
                try {
                    (void)util::is_newer_version(invalid, "0.4.7");
                }
                catch (const std::invalid_argument&) {
                    continue;
                }
                Assert::Fail(L"An invalid version was accepted.");
            }
        }

        TEST_METHOD(is_newer_version_version0) {
            Assert::IsTrue(util::is_newer_version("v0.0.17", "v0.0.9"));
        }

        TEST_METHOD(is_newer_version_version1) {
            Assert::IsTrue(util::is_newer_version("v0.1.0", "v0.0.17"));
        }

        TEST_METHOD(is_newer_version_version11) {
            Assert::IsFalse(util::is_newer_version("v0.0.17", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version12) {
            Assert::IsFalse(util::is_newer_version("v0.1.0", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version13) {
            Assert::IsTrue(util::is_newer_version("v0.2.0", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version2) {
            Assert::IsTrue(util::is_newer_version("v0.17.0", "v0.9.17"));
        }

        TEST_METHOD(is_newer_version_version3) {
            Assert::IsTrue(util::is_newer_version("v12.17.0", "v1.0.0"));
        }

        TEST_METHOD(is_newer_version_version4) {
            Assert::IsFalse(util::is_newer_version("v12.17.0", "v12.17.0"));
        }
    };

    TEST_CLASS(test_update_zip_security)
    {
    public:
        TEST_METHOD(accepts_exact_server_payload)
        {
            const temporary_zip zip(make_stored_zip("AudioShareServer.exe", false));
            std::wstring error;
            Assert::IsTrue(audio_share::updater::validate_update_zip(zip.path, error), error.c_str());
        }

        TEST_METHOD(rejects_path_traversal_name)
        {
            const temporary_zip zip(make_stored_zip("../AudioShareServer.exe", false));
            std::wstring error;
            Assert::IsFalse(audio_share::updater::validate_update_zip(zip.path, error));
        }

        TEST_METHOD(rejects_unreferenced_payload_bytes)
        {
            const temporary_zip zip(make_stored_zip("AudioShareServer.exe", true));
            std::wstring error;
            Assert::IsFalse(audio_share::updater::validate_update_zip(zip.path, error));
        }

        TEST_METHOD(validates_release_artifact_when_requested)
        {
            char* archive_value = nullptr;
            char* signature_value = nullptr;
            std::size_t archive_length = 0;
            std::size_t signature_length = 0;
            _dupenv_s(&archive_value, &archive_length, "AUDIO_SHARE_UPDATE_TEST_ZIP");
            _dupenv_s(&signature_value, &signature_length, "AUDIO_SHARE_UPDATE_TEST_SIGNATURE");
            std::unique_ptr<char, decltype(&std::free)> archive_holder(archive_value, std::free);
            std::unique_ptr<char, decltype(&std::free)> signature_holder(signature_value, std::free);
            if (!archive_value && !signature_value) {
                return;
            }
            Assert::IsNotNull(archive_value, L"AUDIO_SHARE_UPDATE_TEST_ZIP is missing.");
            Assert::IsNotNull(signature_value, L"AUDIO_SHARE_UPDATE_TEST_SIGNATURE is missing.");

            const std::filesystem::path archive(archive_value);
            std::ifstream signature_stream(signature_value, std::ios::binary);
            Assert::IsTrue(static_cast<bool>(signature_stream), L"Failed to open the detached signature.");
            std::string signature{
                std::istreambuf_iterator<char>{ signature_stream },
                std::istreambuf_iterator<char>{}
            };
            signature.erase(std::remove_if(signature.begin(), signature.end(), [](const unsigned char value) {
                return std::isspace(value) != 0;
            }), signature.end());

            std::wstring error;
            Assert::IsTrue(audio_share::updater::validate_update_zip(archive, error), error.c_str());
            Assert::IsTrue(
                audio_share::updater::verify_update_signature(archive, signature, error),
                error.c_str()
            );
        }
    };
}
