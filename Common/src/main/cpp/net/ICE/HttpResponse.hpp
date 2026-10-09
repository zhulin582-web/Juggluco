// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <charconv>
#include <cctype>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>
namespace juggluco_http {
// Fixed diagnostics only: safe to log without exposing response contents.
class ParseError : public std::runtime_error {
  public:
    using std::runtime_error::runtime_error;
};
struct Response {
    int status = -1;
    std::vector<char> body;
    bool complete = false;
    std::string location;
};
// Decode bounded HTTP/1.x framing. Never accept a truncated body as a response.
inline Response parse(std::string_view raw, bool eof = false, unsigned depth = 0) {
    if (depth > 8)
        throw ParseError("Too many interim HTTP responses");
    constexpr size_t limit = 8 * 1024 * 1024;
    Response r;
    if (raw.size() > limit + 65536)
        throw ParseError("HTTP response too large");
    auto end = raw.find("\r\n\r\n");
    if (end == raw.npos) {
        if (raw.size() > 65536 || eof)
            throw ParseError("Incomplete HTTP headers");
        return r;
    }
    if (end > 65536)
        throw ParseError("HTTP headers too large");
    auto line = raw.find("\r\n");
    auto first = raw.substr(0, line);
    if (first.size() < 12 || (!first.starts_with("HTTP/1.1 ") && !first.starts_with("HTTP/1.0 ")))
        throw ParseError("Invalid HTTP status");
    auto [p, err] = std::from_chars(first.data() + 9, first.data() + 12, r.status);
    if (err != std::errc{} || p != first.data() + 12 || r.status < 100 || r.status > 599)
        throw ParseError("Invalid HTTP status");
    std::map<std::string, std::string> headers;
    for (size_t pos = line + 2; pos < end;) {
        auto next = raw.find("\r\n", pos);
        auto value = raw.substr(pos, next - pos);
        auto colon = value.find(':');
        if (colon == value.npos)
            throw ParseError("Invalid HTTP header");
        std::string key(value.substr(0, colon));
        for (auto &c : key)
            c = std::tolower(static_cast<unsigned char>(c));
        value.remove_prefix(colon + 1);
        while (!value.empty() && (value.front() == ' ' || value.front() == '\t'))
            value.remove_prefix(1);
        while (!value.empty() && (value.back() == ' ' || value.back() == '\t'))
            value.remove_suffix(1);
        if (headers.contains(key) &&
            (key == "content-length" || key == "transfer-encoding" || key == "location"))
            throw ParseError("Duplicate HTTP framing or Location");
        headers[key] = value;
        pos = next + 2;
    }
    auto body = raw.substr(end + 4);
    if (r.status >= 100 && r.status < 200) {
        if (r.status == 101)
            throw ParseError("HTTP upgrade unsupported");
        if (body.empty() && !eof)
            return {};
        return parse(body, eof, depth + 1);
    }
    if (auto location = headers.find("location"); location != headers.end())
        r.location = location->second;
    if (r.status == 204 || r.status == 304) {
        r.complete = true;
        return r;
    }
    if (headers.contains("transfer-encoding")) {
        std::string coding = headers["transfer-encoding"];
        for (auto &c : coding)
            c = std::tolower(static_cast<unsigned char>(c));
        if (coding != "chunked" || headers.contains("content-length"))
            throw ParseError("Unsupported HTTP framing");
        size_t at = 0;
        for (;;) {
            auto e = body.find("\r\n", at);
            if (e == body.npos)
                break;
            auto size = body.substr(at, e - at);
            size = size.substr(0, size.find(';'));
            size_t n = 0;
            auto [p, err] = std::from_chars(size.data(), size.data() + size.size(), n, 16);
            if (size.empty() || err != std::errc{} || p != size.data() + size.size() ||
                n > limit - r.body.size())
                throw ParseError("Invalid HTTP chunk");
            at = e + 2;
            if (!n) {
                if (body.substr(at).starts_with("\r\n") || body.find("\r\n\r\n", at) != body.npos) {
                    r.complete = true;
                    return r;
                }
                break;
            }
            if (body.size() - at < n + 2)
                break;
            if (body.substr(at + n, 2) != "\r\n")
                throw ParseError("Invalid chunk delimiter");
            r.body.insert(r.body.end(), body.begin() + at, body.begin() + at + n);
            at += n + 2;
        }
    } else if (headers.contains("content-length")) {
        auto s = headers["content-length"];
        size_t n = 0;
        auto [p, err] = std::from_chars(s.data(), s.data() + s.size(), n);
        if (s.empty() || err != std::errc{} || p != s.data() + s.size() || n > limit)
            throw ParseError("Invalid HTTP length");
        if (body.size() >= n) {
            r.body.assign(body.begin(), body.begin() + n);
            r.complete = true;
            return r;
        }
    } else if (eof) {
        if (body.size() > limit)
            throw ParseError("HTTP body too large");
        r.body.assign(body.begin(), body.end());
        r.complete = true;
        return r;
    }
    if (eof)
        throw ParseError("Truncated HTTP response");
    return r;
}
} // namespace juggluco_http
