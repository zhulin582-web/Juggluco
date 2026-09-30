// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <nlohmann/json.hpp>
#include <optional>
#include <string>

namespace jgchat {
// The caller supplies its own private, no-backup application directory.
class Storage {
public:
    explicit Storage(const std::string& directory);
    ~Storage();
    Storage(const Storage&) = delete;
    Storage& operator=(const Storage&) = delete;
    std::optional<nlohmann::json> read(const char* name) const;
    void write(const char* name, const nlohmann::json&) const;
    void remove(const char* name) const;
private:
    int fd_ = -1;
};
}
