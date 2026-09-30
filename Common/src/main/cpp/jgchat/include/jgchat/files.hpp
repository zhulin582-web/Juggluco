// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "tools.hpp"
#include <mutex>

namespace jgchat {
Json file_tool_definition();
Json prepare_files(const Json&);

// Generated files live below filesDir/additions/chatgpt, separate from account
// credentials and databases. Every answer creates new, immutable bundle IDs.
class FileStore {
public:
    explicit FileStore(const std::string& files_directory);
    ~FileStore();
    FileStore(const FileStore&) = delete;
    FileStore& operator=(const FileStore&) = delete;
    Json save(const Json& bundles, const std::atomic_bool& cancel);
    void rollback(const Json& saved) noexcept;
    Json list() const;
    std::string read_text(const std::string& id, const std::string& file) const;
    std::string relative_path(const std::string& id, const std::string& file) const;
    // Copy to a caller-owned, opened SAF descriptor. No seek/truncate/close;
    // document providers may supply pipes rather than regular files.
    std::size_t copy_to_fd(const std::string& id, const std::string& file, int output) const;
private:
    int fd_ = -1;
    mutable std::recursive_mutex mutex_;
};
}
