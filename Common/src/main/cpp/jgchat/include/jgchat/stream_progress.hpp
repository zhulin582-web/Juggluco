// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "ui_messages.hpp"
#include "tools.hpp"
#include <chrono>
#include <stdexcept>

namespace jgchat {
// Best-effort SSE observer. The authoritative completion parser still validates
// the full stream before any tool runs or conversation changes. This observer
// never displays output text, arguments, encrypted context or raw reasoning.
class StreamProgress {
public:
    explicit StreamProgress(std::function<void(const UiMessage&)> sink) : sink_(std::move(sink)) {}
    void append(std::string_view bytes) {
        for (char c : bytes) {
            if (skip_lf_) { skip_lf_ = false; if (c == '\n') continue; }
            if (c == '\r' || c == '\n') {
                line(); pending_.clear(); skip_lf_ = c == '\r';
            } else if (pending_.size() < maximum_) pending_ += c;
            else discarded_ = true;
        }
    }
private:
    static constexpr std::size_t maximum_ = 128 * 1024;
    std::function<void(const UiMessage&)> sink_;
    std::string pending_, data_, summary_;
    UiMessage last_;
    UiCode stage_ = UiCode::none;
    bool skip_lf_ = false, discarded_ = false;
    std::chrono::steady_clock::time_point last_sent_{};

    static std::string field(const Json& object, const char* key) {
        const auto it = object.find(key);
        return it != object.end() && it->is_string() ? it->get<std::string>() : "";
    }
    void report(UiCode stage, std::string detail = {}) {
        if (detail.size() > 384) {
            std::size_t end = 384;
            while (end && (static_cast<unsigned char>(detail[end]) & 0xc0) == 0x80) --end;
            detail.resize(end);
        }
        const auto message = UiMessage::detail(stage, std::move(detail));
        const auto now = std::chrono::steady_clock::now();
        if (message == last_ || (stage == stage_ && now - last_sent_ < std::chrono::milliseconds(250))) return;
        last_ = message; stage_ = stage; last_sent_ = now;
        if (sink_) sink_(message);
    }
    void dispatch() {
        if (discarded_ || data_.empty() || data_ == "[DONE]\n") return;
        try {
            const auto value = Json::parse(data_, [](int depth, Json::parse_event_t, Json&) {
                if (depth > 32) throw jgchat::UiError(jgchat::UiCode::progress_nesting_limit);
                return true;
            });
            if (!value.is_object()) return;
            const auto type = field(value, "type");
            if (type == "response.created" || type == "response.in_progress") report(UiCode::model_is_working);
            else if (type == "response.output_item.added") {
                const auto item = value.find("item");
                if (item == value.end() || !item->is_object()) return;
                const auto kind = field(*item, "type");
                if (kind == "reasoning") { summary_.clear(); report(UiCode::thinking); }
                else if (kind == "function_call") report(UiCode::preparing_a_data_request);
                else if (kind == "message") report(UiCode::writing_reply);
                else if (kind == "web_search_call") report(UiCode::searching_the_internet);
            } else if (type == "response.reasoning_summary_text.delta") {
                // Only the explicitly public summary, never reasoning_text.*.
                summary_ += field(value, "delta");
                if (summary_.size() > 2048) {
                    std::size_t begin = summary_.size() - 2048;
                    while (begin < summary_.size() && (static_cast<unsigned char>(summary_[begin]) & 0xc0) == 0x80) ++begin;
                    summary_.erase(0, begin);
                }
                report(UiCode::thinking_summary, summary_);
            } else if (type == "response.reasoning_summary_part.added") {
                summary_.clear();
            } else if (type == "response.function_call_arguments.delta") report(UiCode::preparing_a_data_request);
            else if (type.starts_with("response.web_search_call.")) report(UiCode::searching_and_reading_web_pages);
            else if (type == "response.output_text.delta") report(UiCode::writing_reply);
            else if (type == "response.completed") report(UiCode::checking_completed_response);
        } catch (...) { /* Progress is optional; full validation happens later. */ }
    }
    void line() {
        if (pending_.empty()) {
            dispatch(); data_.clear(); discarded_ = false; return;
        }
        if (discarded_ || !pending_.starts_with("data:")) return;
        std::string_view value(pending_);
        value.remove_prefix(5);
        if (!value.empty() && value.front() == ' ') value.remove_prefix(1);
        if (data_.size() + value.size() + 1 > maximum_) { discarded_ = true; return; }
        data_.append(value); data_ += '\n';
    }
};
}
