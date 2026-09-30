// SPDX-License-Identifier: GPL-3.0-or-later
#include "jgchat/storage.hpp"
#include <array>
#include <cerrno>
#include <fcntl.h>
#include <stdexcept>
#include <string_view>
#include <sys/stat.h>
#include <unistd.h>

namespace jgchat {
namespace {
constexpr std::size_t max_file = 8 * 1024 * 1024;
void check_name(std::string_view name) {
    if (name != "tokens.json" && name != "conversation.json")
        throw std::runtime_error("Invalid private storage name");
}
struct File {
    int fd;
    ~File() { if (fd >= 0) ::close(fd); }
};
}
Storage::Storage(const std::string& directory) {
    if (directory.empty() || directory.front() != '/')
        throw std::runtime_error("Application storage directory must be absolute");
    if (::mkdir(directory.c_str(), 0700) != 0 && errno != EEXIST)
        throw std::runtime_error("Cannot create private chat storage");
    fd_ = ::open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd_ < 0) throw std::runtime_error("Cannot open private chat storage");
    if (::fchmod(fd_, 0700) != 0) {
        ::close(fd_); fd_ = -1;
        throw std::runtime_error("Cannot restrict private chat storage permissions");
    }
}
Storage::~Storage() { if (fd_ >= 0) ::close(fd_); }
std::optional<nlohmann::json> Storage::read(const char* name) const {
    check_name(name);
    File file{::openat(fd_, name, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW)};
    if (file.fd < 0) {
        if (errno == ENOENT) return std::nullopt;
        throw std::runtime_error("Cannot open saved chat state");
    }
    struct stat st{};
    if (::fstat(file.fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0 ||
        static_cast<unsigned long long>(st.st_size) > max_file)
        throw std::runtime_error("Saved chat state is not a bounded regular file");
    if (::fchmod(file.fd, 0600) != 0)
        throw std::runtime_error("Cannot restrict saved chat state permissions");
    std::string text;
    std::array<char, 8192> buffer{};
    for (;;) {
        const auto n = ::read(file.fd, buffer.data(), buffer.size());
        if (n == 0) break;
        if (n < 0) { if (errno == EINTR) continue; throw std::runtime_error("Cannot read saved chat state"); }
        if (text.size() + static_cast<std::size_t>(n) > max_file)
            throw std::runtime_error("Saved chat state is too large");
        text.append(buffer.data(), static_cast<std::size_t>(n));
    }
    try { return nlohmann::json::parse(text); }
    catch (...) { throw std::runtime_error("Saved chat state is invalid; sign in again or start a new chat"); }
}
void Storage::write(const char* name, const nlohmann::json& value) const {
    check_name(name);
    const std::string data = value.dump();
    if (data.size() > max_file) throw std::runtime_error("Chat state is too large; start a new chat");
    const std::string temporary = std::string(name) + ".tmp";
    if (::unlinkat(fd_, temporary.c_str(), 0) != 0 && errno != ENOENT)
        throw std::runtime_error("Cannot replace temporary chat state");
    File file{::openat(fd_, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600)};
    if (file.fd < 0) throw std::runtime_error("Cannot create private chat state");
    try {
        for (std::size_t pos = 0; pos < data.size();) {
            const auto n = ::write(file.fd, data.data() + pos, data.size() - pos);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) throw std::runtime_error("Cannot write private chat state");
            pos += static_cast<std::size_t>(n);
        }
        if (::fsync(file.fd) != 0 || ::renameat(fd_, temporary.c_str(), fd_, name) != 0)
            throw std::runtime_error("Cannot save private chat state");
        // Some Android filesystems do not support directory fsync. The file was
        // already flushed and atomically renamed; do not undo that valid state.
        ::fsync(fd_);
    } catch (...) {
        ::unlinkat(fd_, temporary.c_str(), 0);
        throw;
    }
}
void Storage::remove(const char* name) const {
    check_name(name);
    if (::unlinkat(fd_, name, 0) != 0 && errno != ENOENT)
        throw std::runtime_error("Cannot remove saved chat state");
    const std::string temporary = std::string(name) + ".tmp";
    if (::unlinkat(fd_, temporary.c_str(), 0) != 0 && errno != ENOENT)
        throw std::runtime_error("Cannot remove temporary chat state");
    ::fsync(fd_);
}
}
