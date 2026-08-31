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

#ifndef NETWORK_MANAGER_HPP
#define NETWORK_MANAGER_HPP

#include <memory>
#include <vector>
#include <string>
#include <map>
#include <deque>
#include <array>
#include <chrono>

#include "pre_asio.hpp"
#include <asio.hpp>
#include <asio/use_awaitable.hpp>

#include "audio_manager.hpp"

class network_manager : public std::enable_shared_from_this<network_manager>
{
    using default_token = asio::as_tuple_t<asio::use_awaitable_t<>>;
    using tcp_acceptor = default_token::as_default_on_t<asio::ip::tcp::acceptor>;
    using tcp_socket = default_token::as_default_on_t<asio::ip::tcp::socket>;
    using udp_socket = default_token::as_default_on_t<asio::ip::udp::socket>;
    using steady_timer = default_token::as_default_on_t<asio::steady_timer>;

    struct peer_info_t {
        int id = 0;
        bool protocol_v2 = false;
        uint64_t session_id = 0;
        asio::ip::udp::endpoint udp_peer;
        std::chrono::steady_clock::time_point last_tick;
    };

#pragma pack(push, 1)
    struct udp_v2_header_t {
        uint32_t magic;
        uint8_t version;
        uint8_t flags;
        uint16_t header_size;
        uint64_t session_id;
        uint32_t sequence;
        uint64_t frame_index;
        uint16_t frame_count;
        uint16_t payload_size;
    };
#pragma pack(pop)

    static_assert(sizeof(udp_v2_header_t) == 32);

    struct outgoing_datagram_t {
        std::shared_ptr<std::vector<uint8_t>> bytes;
        asio::ip::udp::endpoint endpoint;
    };

    struct packet_record_t {
        uint32_t sequence = 0;
        std::shared_ptr<std::vector<uint8_t>> bytes;
        std::chrono::steady_clock::time_point created_at;
    };

    using playing_peer_list_t = std::map<std::shared_ptr<tcp_socket>, std::shared_ptr<peer_info_t>>;

    enum class cmd_t : uint32_t {
        cmd_none = 0,
        cmd_get_format = 1,
        cmd_start_play = 2,
        cmd_heartbeat = 3,
        cmd_hello_v2 = 4,
    };

    enum udp_v2_flag_t : uint8_t {
        udp_flag_audio = 1,
        udp_flag_registration = 2,
        udp_flag_nack = 4,
        udp_flag_retransmitted = 8,
    };

public:

    explicit network_manager(std::shared_ptr<audio_manager>& audio_manager);

    static std::vector<std::string> get_address_list();
    static std::string get_default_address();
private:
    static std::string select_default_address(const std::vector<std::string>& address_list);

public:
    void start_server(const std::string& host, uint16_t port, const audio_manager::capture_config& capture_config);
    void stop_server();
    void wait_server();
    bool is_running() const;

private:
    asio::awaitable<void> accept_tcp_loop(tcp_acceptor acceptor);
    asio::awaitable<void> read_loop(std::shared_ptr<tcp_socket> peer);
    asio::awaitable<void> heartbeat_loop(std::shared_ptr<tcp_socket> peer);
    asio::awaitable<void> accept_udp_loop();
    
    playing_peer_list_t::iterator close_session(std::shared_ptr<tcp_socket>& peer);
    int add_playing_peer(std::shared_ptr<tcp_socket>& peer, bool protocol_v2);
    playing_peer_list_t::iterator remove_playing_peer(std::shared_ptr<tcp_socket>& peer);
    void fill_udp_peer(int id, asio::ip::udp::endpoint udp_peer);
    void fill_udp_peer(uint64_t session_id, asio::ip::udp::endpoint udp_peer);
    void handle_v2_datagram(const uint8_t* data, size_t size, const asio::ip::udp::endpoint& udp_peer);
    void enqueue_udp(std::shared_ptr<std::vector<uint8_t>> bytes, const asio::ip::udp::endpoint& endpoint);
    void send_next_udp();
    void prune_packet_ring();
    std::shared_ptr<std::vector<uint8_t>> packet_for_session(const std::vector<uint8_t>& packet, uint64_t session_id, bool retransmitted) const;

public:
    void broadcast_audio_data(const char* data, size_t count, int block_align);
    
    std::shared_ptr<asio::io_context> _ioc;

private:
    std::shared_ptr<audio_manager> _audio_manager;
    std::thread _net_thread;
    std::unique_ptr<udp_socket> _udp_server;
    playing_peer_list_t _playing_peer_list;
    std::deque<outgoing_datagram_t> _send_queue;
    std::deque<packet_record_t> _packet_ring;
    std::vector<uint8_t> _v2_pending;
    uint32_t _next_sequence = 0;
    uint64_t _next_frame_index = 0;
    constexpr static auto _heartbeat_timeout = std::chrono::seconds(5);
    constexpr static auto _retransmit_window = std::chrono::milliseconds(250);
    constexpr static uint32_t _udp_v2_magic = 0x32534141; // "AAS2" in little endian
    constexpr static uint8_t _protocol_v2 = 2;
    constexpr static size_t _max_datagram_size = 1200;
};
#endif // !NETWORK_MANAGER_HPP
