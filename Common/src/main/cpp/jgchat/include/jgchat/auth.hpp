// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "http.hpp"
#include <nlohmann/json.hpp>
#include <cstdint>

namespace jgchat {
using Json = nlohmann::json;
struct Tokens {
    std::string access_token, refresh_token, id_token, account_id;
    std::int64_t expires_at = 0;
};
struct DeviceCode {
    std::string verification_url, user_code, device_auth_id;
    unsigned interval_seconds = 5;
    std::chrono::steady_clock::time_point deadline;
};
DeviceCode request_device_code(const HttpClient&, const std::atomic_bool&);
Tokens complete_device_login(const HttpClient&, const DeviceCode&, const std::atomic_bool&);
Tokens refresh_tokens(const HttpClient&, const Tokens&, const std::atomic_bool&);
Json tokens_to_json(const Tokens&);
Tokens tokens_from_json(const Json&);
}
