#pragma once

#include <string>
#include <string_view>

namespace jgchatdata {

// Call while the local user has enabled data tools and Juggluco's
// settings/number/sensor stores are initialized. target is a path plus query,
// never an HTTP message. The result is one complete HTTP response.
// No request modifies records, settings, files, or the normal web server.
std::string handle_request(std::string_view target);

struct WebConfig { bool enabled{}; unsigned port{}; std::string secret; };
// Only enabled/port/authentication-required are model-visible. The secret is
// used locally to construct a browser URL and must never enter tools or logs.
WebConfig web_config();
std::string web_file_url(std::string_view relative);

} // namespace jgchatdata
