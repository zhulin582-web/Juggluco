// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "jgchat/http.hpp"
#include <optional>
#include <string_view>

namespace jgchat {
// Shared by both HTTPS transports. Throws only generic errors, never request
// header values, credentials, URL queries, or request/response bodies.
void validate_http_request(const HttpRequest& request);
std::string serialize_http_request(const HttpRequest& request);

class HttpResponseParser {
public:
    explicit HttpResponseParser(std::size_t maximum_body = 4 * 1024 * 1024,
                                HttpBodySink on_body = {});
    void append(std::string_view bytes);
    bool complete() const;
    HttpResponse finish(); // EOF: rejects incomplete framed responses.
    HttpResponse result() const; // Requires complete().
private:
    enum class State { headers, fixed, chunk_size, chunk_data, trailers, eof_body, done };
    State state_ = State::headers;
    std::size_t maximum_, wire_bytes_ = 0, remaining_ = 0, trailer_bytes_ = 0;
    unsigned interim_ = 0;
    std::string pending_;
    HttpResponse response_;
    HttpBodySink on_body_;
    void body(std::string_view bytes);
    void process();
};

std::optional<HttpResponse> parse_http_response(std::string_view raw, bool eof = false,
                                               std::size_t maximum_body = 4 * 1024 * 1024);
}
