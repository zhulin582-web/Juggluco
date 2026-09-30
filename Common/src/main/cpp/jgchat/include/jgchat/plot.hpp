// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "tools.hpp"
namespace jgchat {
// Render only locally exported readings; no model-supplied points, code or SVG.
// The returned plot is a local presentation attachment, removed before sending
// the normal data result back to OpenAI.
Json glucose_plot(Json glucose);
// General line/scatter/bar plotting from explicitly supplied numeric data.
Json xy_plot(const Json& specification, bool from_saved_table = false);
}
