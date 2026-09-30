// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "auth.hpp"
#include "tools.hpp"

namespace jgchat {
class Workspace;
// Events are small UI messages such as a tool name or a progress indication;
// never credentials or hidden reasoning.
using EventSink = std::function<void(std::string_view)>;
using TokenSink = std::function<void(const Tokens&)>;
class ChatClient {
public:
    ChatClient(HttpClient, Tokens, ToolHandler, TokenSink, Workspace* workspace = nullptr);
    // Includes normalized reasoning_efforts and recommended_reasoning_effort.
    // No hard-coded default model; effort choices come from account metadata.
    Json list_models(const std::atomic_bool& cancel);
    std::string ask(const std::string& question, const std::string& model,
                    const std::atomic_bool& cancel, const EventSink& event,
                    const std::string& reasoning_effort = {}, bool internet = true);
    void reset();
    Json history() const;
    Json plots() const; // Local attachments for the last successful answer.
    Json files() const; // Validated bundles, saved by the Android bridge on commit.
    Json citations() const;
    void restore_history(const Json&);
private:
    HttpClient http_;
    Tokens tokens_;
    ToolHandler tool_;
    TokenSink save_tokens_;
    Workspace* workspace_;
    Json history_ = Json::array();
    Json models_ = Json::array();
    Json plots_ = Json::array();
    Json files_ = Json::array(), citations_ = Json::array();
    HttpResponse authenticated(HttpRequest, const std::atomic_bool&);
};
}
