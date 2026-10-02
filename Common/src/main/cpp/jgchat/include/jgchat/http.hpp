// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "ui_messages.hpp"
#include <atomic>
#include <chrono>
#include <functional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace jgchat {
// Decoded successful-response body bytes, synchronously during the request.
// Presentation only: may be partial/unvalidated; never execute tools from it.
using HttpBodySink = std::function<void(std::string_view)>;
struct HttpRequest {
    std::string host;
    std::string path;
    std::string method = "POST";
    std::string body;
    std::vector<std::pair<std::string, std::string>> headers;
    std::chrono::milliseconds timeout{60000};
    std::size_t max_response_bytes = 4 * 1024 * 1024;
    unsigned short port = 443;
    HttpBodySink on_body;
};
struct HttpResponse {
    long status = 0;
    std::string body;
    std::string error;
    UiMessage ui_error{};
};
inline HttpResponse http_failure(UiMessage message) {
    return {0, {}, ui_diagnostic(message), std::move(message)};
}
using HttpClient = std::function<HttpResponse(const HttpRequest&, const std::atomic_bool&)>;
HttpClient native_https();
HttpClient curl_https();
}
