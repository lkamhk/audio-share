/*
   Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

#include "network_manager.hpp"
#include "formatter.hpp"
#include "audio_manager.hpp"

#include <list>
#include <ranges>
#include <coroutine>
#include <random>
#include <cstring>
#include <span>

#ifdef _WINDOWS
#include <iphlpapi.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "Ws2_32.lib")
#pragma comment(lib, "Iphlpapi.lib")
#endif // _WINDOWS

#ifdef linux
#include <sys/types.h>
#include <ifaddrs.h>
#endif

#include <spdlog/spdlog.h>
#include <fmt/ranges.h>

namespace ip = asio::ip;
using namespace std::chrono_literals;

network_manager::network_manager(std::shared_ptr<audio_manager>& audio_manager)
    : _audio_manager(audio_manager)
{
}

std::vector<std::string> network_manager::get_address_list()
{
    std::vector<std::string> address_list;

#ifdef _WINDOWS
    ULONG family = AF_INET;
    ULONG flags = GAA_FLAG_INCLUDE_ALL_INTERFACES;

    ULONG size = 0;
    GetAdaptersAddresses(family, flags, nullptr, nullptr, &size);
    auto pAddresses = (PIP_ADAPTER_ADDRESSES)malloc(size);

    auto ret = GetAdaptersAddresses(family, flags, nullptr, pAddresses, &size);
    if (ret == ERROR_SUCCESS) {
        for (auto pCurrentAddress = pAddresses; pCurrentAddress; pCurrentAddress = pCurrentAddress->Next) {
            if (pCurrentAddress->OperStatus != IfOperStatusUp || pCurrentAddress->IfType == IF_TYPE_SOFTWARE_LOOPBACK) {
                continue;
            }

            for (auto pUnicast = pCurrentAddress->FirstUnicastAddress; pUnicast; pUnicast = pUnicast->Next) {
                auto sockaddr = (sockaddr_in*)pUnicast->Address.lpSockaddr;
                char buf[50];
                if (inet_ntop(AF_INET, &sockaddr->sin_addr, buf, sizeof(buf))) {
                    address_list.emplace_back(buf);
                }
            }
        }
    }

    free(pAddresses);
#endif

#ifdef linux
    struct ifaddrs* ifaddrs;
    if (getifaddrs(&ifaddrs) == -1) {
        return address_list;
    }

    for (auto ifa = ifaddrs; ifa; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) {
            continue;
        }
        if (ifa->ifa_addr->sa_family != AF_INET) {
            continue;
        }
        if (ifa->ifa_flags & IFF_LOOPBACK) {
            continue;
        }
        auto sockaddr = (sockaddr_in*)ifa->ifa_addr;
        char buf[50];
        if (inet_ntop(AF_INET, &sockaddr->sin_addr, buf, sizeof(buf))) {
            address_list.emplace_back(buf);
        }
    }

    freeifaddrs(ifaddrs);
#endif

    return address_list;
}

std::string network_manager::get_default_address()
{
    return select_default_address(get_address_list());
}

std::string network_manager::select_default_address(const std::vector<std::string>& address_list)
{
    if (address_list.empty()) {
        return {};
    }

    auto is_private_address = [](const std::string& address) {
        constexpr uint32_t private_addr_list[] = {
            0x0a000000,
            0xac100000,
            0xc0a80000,
        };

        uint32_t addr;
        inet_pton(AF_INET, address.c_str(), &addr);
        addr = ntohl(addr);
        for (auto&& private_addr : private_addr_list) {
            if ((addr & private_addr) == private_addr) {
                return true;
            }
        }

        return false;
    };

    for (auto&& address : address_list) {
        if (is_private_address(address)) {
            return address;
        }
    }
    return address_list.front();
}

void network_manager::start_server(const std::string& host, uint16_t port, const audio_manager::capture_config& capture_config)
{
    _send_queue.clear();
    _packet_ring.clear();
    _v2_pending.clear();
    _next_sequence = 0;
    _next_frame_index = 0;
    _ioc = std::make_shared<asio::io_context>();
    {
        ip::tcp::endpoint endpoint { ip::make_address(host), port };

        ip::tcp::acceptor acceptor(*_ioc, endpoint.protocol());
        acceptor.set_option(ip::tcp::acceptor::reuse_address(true));
        acceptor.bind(endpoint);
        acceptor.listen();

        _audio_manager->start_loopback_recording(shared_from_this(), capture_config);
        asio::co_spawn(*_ioc, accept_tcp_loop(std::move(acceptor)), asio::detached);

        // start tcp success
        spdlog::info("tcp listen success on {}", endpoint);
    }

    {
        ip::udp::endpoint endpoint { ip::make_address(host), port };
        _udp_server = std::make_unique<udp_socket>(*_ioc, endpoint.protocol());
        _udp_server->bind(endpoint);
        asio::co_spawn(*_ioc, accept_udp_loop(), asio::detached);

        // start udp success
        spdlog::info("udp listen success on {}", endpoint);
    }

    _net_thread = std::thread([self = shared_from_this()] {
        self->_ioc->run();
    });

    spdlog::info("server started");
}

void network_manager::stop_server()
{
    if (_ioc) {
        _ioc->stop();
    }
    _net_thread.join();
    _audio_manager->stop();
    _playing_peer_list.clear();
    _udp_server = nullptr;
    _ioc = nullptr;
    spdlog::info("server stopped");
}

void network_manager::wait_server()
{
    _net_thread.join();
}

bool network_manager::is_running() const
{
    return _ioc != nullptr;
}

asio::awaitable<void> network_manager::read_loop(std::shared_ptr<tcp_socket> peer)
{
    bool protocol_v2 = false;
    while (true) {
        cmd_t cmd = cmd_t::cmd_none;
        auto [ec, _] = co_await asio::async_read(*peer, asio::buffer(&cmd, sizeof(cmd)));
        if (ec) {
            close_session(peer);
            spdlog::trace("{} {}", __func__, ec);
            break;
        }

        spdlog::trace("cmd {}", (uint32_t)cmd);

        if (cmd == cmd_t::cmd_hello_v2) {
            io::github::mkckr0::audio_share_app::pb::Capabilities capabilities;
            capabilities.set_protocol_version(_protocol_v2);
            capabilities.set_max_datagram_bytes(static_cast<uint32_t>(_max_datagram_size));
            capabilities.set_retransmit_window_ms(static_cast<uint32_t>(_retransmit_window.count()));
            auto payload = capabilities.SerializeAsString();
            auto size = static_cast<uint32_t>(payload.size());
            std::array<asio::const_buffer, 3> buffers = {
                asio::buffer(&cmd, sizeof(cmd)),
                asio::buffer(&size, sizeof(size)),
                asio::buffer(payload),
            };
            auto [ec, _] = co_await asio::async_write(*peer, buffers);
            if (ec) {
                close_session(peer);
                break;
            }
            protocol_v2 = true;
        } else if (cmd == cmd_t::cmd_get_format) {
            auto format = _audio_manager->get_format_binary();
            auto size = (uint32_t)format.size();
            std::array<asio::const_buffer, 3> buffers = {
                asio::buffer(&cmd, sizeof(cmd)),
                asio::buffer(&size, sizeof(size)),
                asio::buffer(format),
            };
            auto [ec, _] = co_await asio::async_write(*peer, buffers);
            if (ec) {
                close_session(peer);
                spdlog::trace("{} {}", __func__, ec);
                break;
            }
        } else if (cmd == cmd_t::cmd_start_play) {
            int id = add_playing_peer(peer, protocol_v2);
            if (id <= 0) {
                spdlog::error("{} id error", __func__);
                close_session(peer);
                spdlog::trace("{} {}", __func__, ec);
                break;
            }
            auto info = _playing_peer_list.at(peer);
            std::array<asio::const_buffer, 3> buffers = {
                asio::buffer(&cmd, sizeof(cmd)),
                asio::buffer(&id, sizeof(id)),
                protocol_v2 ? asio::buffer(&info->session_id, sizeof(info->session_id)) : asio::const_buffer(),
            };
            auto buffer_count = protocol_v2 ? buffers.size() : buffers.size() - 1;
            auto [ec, _] = co_await asio::async_write(*peer, std::span(buffers.data(), buffer_count));
            if (ec) {
                spdlog::trace("{} {}", __func__, ec);
                close_session(peer);
                break;
            }
            asio::co_spawn(*_ioc, heartbeat_loop(peer), asio::detached);
        } else if (cmd == cmd_t::cmd_heartbeat) {
            auto it = _playing_peer_list.find(peer);
            if (it != _playing_peer_list.end()) {
                it->second->last_tick = std::chrono::steady_clock::now();
            }
        } else {
            spdlog::error("{} error cmd", __func__);
            close_session(peer);
            break;
        }
    }
    spdlog::trace("stop {}", __func__);
}

asio::awaitable<void> network_manager::heartbeat_loop(std::shared_ptr<tcp_socket> peer)
{
    std::error_code ec;
    size_t _;

    steady_timer timer(*_ioc);
    while (true) {
        timer.expires_after(3s);
        std::tie(ec) = co_await timer.async_wait();
        if (ec) {
            break;
        }

        if (!peer->is_open()) {
            break;
        }

        auto it = _playing_peer_list.find(peer);
        if (it == _playing_peer_list.end()) {
            spdlog::trace("{} it == _playing_peer_list.end()", __func__);
            close_session(peer);
            break;
        }
        if (std::chrono::steady_clock::now() - it->second->last_tick > _heartbeat_timeout) {
            spdlog::info("{} timeout", it->first->remote_endpoint());
            close_session(peer);
            break;
        }

        auto cmd = cmd_t::cmd_heartbeat;
        std::tie(ec, _) = co_await asio::async_write(*peer, asio::buffer(&cmd, sizeof(cmd)));
        if (ec) {
            spdlog::trace("{} {}", __func__, ec);
            close_session(peer);
            break;
        }
    }
    spdlog::trace("stop {}", __func__);
}

asio::awaitable<void> network_manager::accept_tcp_loop(tcp_acceptor acceptor)
{
    while (true) {
        auto peer = std::make_shared<tcp_socket>(acceptor.get_executor());
        auto [ec] = co_await acceptor.async_accept(*peer);
        if (ec) {
            spdlog::error("{} {}", __func__, ec);
            co_return;
        }

        spdlog::info("accept {}", peer->remote_endpoint());

        // No-Delay
        peer->set_option(ip::tcp::no_delay(true), ec);
        if (ec) {
            spdlog::info("{} {}", __func__, ec);
        }

        asio::co_spawn(acceptor.get_executor(), read_loop(peer), asio::detached);
    }
}

asio::awaitable<void> network_manager::accept_udp_loop()
{
    while (true) {
        std::array<uint8_t, _max_datagram_size> data {};
        ip::udp::endpoint udp_peer;
        auto [ec, size] = co_await _udp_server->async_receive_from(asio::buffer(data), udp_peer);
        if (ec) {
            spdlog::info("{} {}", __func__, ec);
            co_return;
        }

        if (size >= sizeof(udp_v2_header_t)) {
            udp_v2_header_t header {};
            std::memcpy(&header, data.data(), sizeof(header));
            if (header.magic == _udp_v2_magic && header.version == _protocol_v2) {
                handle_v2_datagram(data.data(), size, udp_peer);
                continue;
            }
        }
        if (size == sizeof(int)) {
            int id = 0;
            std::memcpy(&id, data.data(), sizeof(id));
            fill_udp_peer(id, udp_peer);
        }
    }
}

auto network_manager::close_session(std::shared_ptr<tcp_socket>& peer) -> playing_peer_list_t::iterator
{
    spdlog::info("close {}", peer->remote_endpoint());
    auto it = remove_playing_peer(peer);
    peer->shutdown(ip::tcp::socket::shutdown_both);
    peer->close();
    return it;
}

int network_manager::add_playing_peer(std::shared_ptr<tcp_socket>& peer, bool protocol_v2)
{
    if (_playing_peer_list.contains(peer)) {
        spdlog::error("{} repeat add tcp://{}", __func__, peer->remote_endpoint());
        return 0;
    }

    auto info = _playing_peer_list[peer] = std::make_shared<peer_info_t>();
    static int g_id = 0;
    info->id = ++g_id;
    info->protocol_v2 = protocol_v2;
    if (protocol_v2) {
        static std::mt19937_64 generator(std::random_device {}());
        do {
            info->session_id = generator();
        } while (info->session_id == 0);
    }
    info->last_tick = std::chrono::steady_clock::now();

    spdlog::trace("{} add id:{} tcp://{}", __func__, info->id, peer->remote_endpoint());
    return info->id;
}

auto network_manager::remove_playing_peer(std::shared_ptr<tcp_socket>& peer) -> playing_peer_list_t::iterator
{
    auto it = _playing_peer_list.find(peer);
    if (it == _playing_peer_list.end()) {
        spdlog::error("{} repeat remove tcp://{}", __func__, peer->remote_endpoint());
        return it;
    }

    it = _playing_peer_list.erase(it);
    spdlog::trace("{} remove tcp://{}", __func__, peer->remote_endpoint());
    return it;
}

void network_manager::fill_udp_peer(int id, asio::ip::udp::endpoint udp_peer)
{
    auto it = std::find_if(_playing_peer_list.begin(), _playing_peer_list.end(), [id](const playing_peer_list_t::value_type& e) {
        return e.second->id == id;
    });

    if (it == _playing_peer_list.cend()) {
        spdlog::error("{} no tcp peer id:{} udp://{}", __func__, id, udp_peer);
        return;
    }

    it->second->udp_peer = udp_peer;
    spdlog::info("{} fill udp peer id:{} tcp://{} udp://{}", __func__, id, it->first->remote_endpoint(), udp_peer);
}

void network_manager::fill_udp_peer(uint64_t session_id, asio::ip::udp::endpoint udp_peer)
{
    auto it = std::find_if(_playing_peer_list.begin(), _playing_peer_list.end(), [session_id](const playing_peer_list_t::value_type& e) {
        return e.second->protocol_v2 && e.second->session_id == session_id;
    });
    if (it == _playing_peer_list.end()) {
        spdlog::warn("{} unknown v2 session:{} udp://{}", __func__, session_id, udp_peer);
        return;
    }
    it->second->udp_peer = udp_peer;
    spdlog::info("{} fill v2 udp peer session:{} tcp://{} udp://{}", __func__, session_id, it->first->remote_endpoint(), udp_peer);
}

void network_manager::handle_v2_datagram(const uint8_t* data, size_t size, const asio::ip::udp::endpoint& udp_peer)
{
    udp_v2_header_t header {};
    std::memcpy(&header, data, sizeof(header));
    if (header.header_size != sizeof(header) || header.payload_size + header.header_size != size) {
        spdlog::warn("drop malformed v2 datagram from udp://{}", udp_peer);
        return;
    }
    if (header.flags & udp_flag_registration) {
        fill_udp_peer(header.session_id, udp_peer);
        return;
    }
    if (!(header.flags & udp_flag_nack)) {
        return;
    }
    auto peer = std::find_if(_playing_peer_list.begin(), _playing_peer_list.end(), [&](const playing_peer_list_t::value_type& entry) {
        return entry.second->protocol_v2 && entry.second->session_id == header.session_id && entry.second->udp_peer == udp_peer;
    });
    if (peer == _playing_peer_list.end()) {
        return;
    }
    auto packet = std::find_if(_packet_ring.begin(), _packet_ring.end(), [&](const packet_record_t& record) {
        return record.sequence == header.sequence;
    });
    if (packet != _packet_ring.end()) {
        enqueue_udp(packet_for_session(*packet->bytes, header.session_id, true), udp_peer);
    }
}

std::shared_ptr<std::vector<uint8_t>> network_manager::packet_for_session(const std::vector<uint8_t>& packet, uint64_t session_id, bool retransmitted) const
{
    auto result = std::make_shared<std::vector<uint8_t>>(packet);
    udp_v2_header_t header {};
    std::memcpy(&header, result->data(), sizeof(header));
    header.session_id = session_id;
    if (retransmitted) {
        header.flags |= udp_flag_retransmitted;
    }
    std::memcpy(result->data(), &header, sizeof(header));
    return result;
}

void network_manager::enqueue_udp(std::shared_ptr<std::vector<uint8_t>> bytes, const asio::ip::udp::endpoint& endpoint)
{
    if (endpoint.port() == 0) {
        return;
    }
    bool idle = _send_queue.empty();
    _send_queue.push_back({ std::move(bytes), endpoint });
    if (idle) {
        send_next_udp();
    }
}

void network_manager::send_next_udp()
{
    if (_send_queue.empty() || !_udp_server) {
        return;
    }
    auto& item = _send_queue.front();
    _udp_server->async_send_to(asio::buffer(*item.bytes), item.endpoint, [self = shared_from_this()](const asio::error_code& ec, std::size_t) {
        if (ec) {
            spdlog::warn("udp send failed: {}", ec.message());
        }
        self->_send_queue.pop_front();
        self->send_next_udp();
    });
}

void network_manager::prune_packet_ring()
{
    auto cutoff = std::chrono::steady_clock::now() - _retransmit_window;
    while (!_packet_ring.empty() && _packet_ring.front().created_at < cutoff) {
        _packet_ring.pop_front();
    }
}
void network_manager::broadcast_audio_data(const char* data, size_t count, int block_align)
{
    if (count <= 0) {
        return;
    }
    // spdlog::trace("broadcast_audio_data count: {}", count);

    // Divide legacy UDP frames.
    constexpr int mtu = 1492;
    int max_seg_size = mtu - 20 - 8;
    max_seg_size -= max_seg_size % block_align; // one single sample can't be divided

    std::list<std::shared_ptr<std::vector<uint8_t>>> seg_list;

    for (int begin_pos = 0; begin_pos < count;) {
        const int real_seg_size = std::min((int)count - begin_pos, max_seg_size);
        auto seg = std::make_shared<std::vector<uint8_t>>(real_seg_size);
        std::copy((const uint8_t*)data + begin_pos, (const uint8_t*)data + begin_pos + real_seg_size, seg->begin());
        seg_list.push_back(seg);
        begin_pos += real_seg_size;
    }

    // V2 uses conservative IPv4/IPv6-safe datagrams and keeps a small carry so
    // all regular packets have a predictable frame-aligned payload size.
    const size_t v2_max_payload = (_max_datagram_size - sizeof(udp_v2_header_t))
        - (_max_datagram_size - sizeof(udp_v2_header_t)) % block_align;
    _v2_pending.insert(_v2_pending.end(), reinterpret_cast<const uint8_t*>(data), reinterpret_cast<const uint8_t*>(data) + count);
    std::vector<std::shared_ptr<std::vector<uint8_t>>> v2_packets;
    while (_v2_pending.size() >= v2_max_payload) {
        auto packet = std::make_shared<std::vector<uint8_t>>(sizeof(udp_v2_header_t) + v2_max_payload);
        udp_v2_header_t header {
            .magic = _udp_v2_magic,
            .version = _protocol_v2,
            .flags = udp_flag_audio,
            .header_size = static_cast<uint16_t>(sizeof(udp_v2_header_t)),
            .session_id = 0,
            .sequence = _next_sequence++,
            .frame_index = _next_frame_index,
            .frame_count = static_cast<uint16_t>(v2_max_payload / block_align),
            .payload_size = static_cast<uint16_t>(v2_max_payload),
        };
        _next_frame_index += header.frame_count;
        std::memcpy(packet->data(), &header, sizeof(header));
        std::copy_n(_v2_pending.begin(), v2_max_payload, packet->begin() + sizeof(header));
        _v2_pending.erase(_v2_pending.begin(), _v2_pending.begin() + v2_max_payload);
        v2_packets.push_back(std::move(packet));
    }

    _ioc->post([seg_list = std::move(seg_list), v2_packets = std::move(v2_packets), self = shared_from_this()] {
        for (const auto& seg : seg_list) {
            for (auto& [peer, info] : self->_playing_peer_list) {
                if (!info->protocol_v2) {
                    self->enqueue_udp(seg, info->udp_peer);
                }
            }
        }
        for (const auto& packet : v2_packets) {
            udp_v2_header_t header {};
            std::memcpy(&header, packet->data(), sizeof(header));
            self->_packet_ring.push_back({ header.sequence, packet, std::chrono::steady_clock::now() });
            for (auto& [peer, info] : self->_playing_peer_list) {
                if (info->protocol_v2) {
                    self->enqueue_udp(self->packet_for_session(*packet, info->session_id, false), info->udp_peer);
                }
            }
        }
        self->prune_packet_ring();
    });
}
