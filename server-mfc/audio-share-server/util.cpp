#include "util.hpp"

#include <array>
#include <charconv>
#include <stdexcept>
#include <string_view>

namespace {

std::array<unsigned int, 3> parse_version(std::string_view version)
{
    if (version.starts_with('v')) {
        version.remove_prefix(1);
    }
    if (version.empty()) {
        throw std::invalid_argument("is_newer_version: bad arguments");
    }

    std::array<unsigned int, 3> result{};
    for (std::size_t index = 0; index < result.size(); ++index) {
        const auto separator = version.find('.');
        const auto is_last = index + 1 == result.size();
        if ((is_last && separator != std::string_view::npos) ||
            (!is_last && separator == std::string_view::npos)) {
            throw std::invalid_argument("is_newer_version: bad arguments");
        }

        const auto component = is_last ? version : version.substr(0, separator);
        if (component.empty()) {
            throw std::invalid_argument("is_newer_version: bad arguments");
        }

        const auto [end, error] = std::from_chars(
            component.data(), component.data() + component.size(), result[index]
        );
        if (error != std::errc{} || end != component.data() + component.size()) {
            throw std::invalid_argument("is_newer_version: bad arguments");
        }

        if (!is_last) {
            version.remove_prefix(separator + 1);
        }
    }
    return result;
}

} // namespace

namespace util {

    bool is_newer_version(const std::string& lhs, const std::string& rhs)
    {
        return parse_version(lhs) > parse_version(rhs);
    }

    // empty substring will be ignored
    std::vector<std::string> split_string(const std::string& src, char delimiter)
    {
        std::vector<std::string> result;
        size_t begin = 0, end = 0;
        while (begin < src.length()) {
            end = src.find(delimiter, begin);
            if (end == src.npos) {
                result.push_back(src.substr(begin, src.npos));
                break;
            }
            if (end - begin > 0) {
                result.push_back(src.substr(begin, end - begin));
            }
            begin = end + 1;
        }

        return result;
    }
}
