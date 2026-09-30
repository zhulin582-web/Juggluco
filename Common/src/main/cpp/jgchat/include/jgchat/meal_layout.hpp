// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>

namespace jgchatdata {
// On-disk prefix of Juggluco's mealdata. The production adapter checks these
// layouts against Meal.hpp at compile time. Read copies with pread, never keep
// a pointer into the mutable/remappable meal store on the chat worker thread.
struct MealIngredient {
    int32_t unit, used;
    float carb;
    std::array<char, 40> name;
};
struct MealHeader {
    uint8_t unitnr, reserved[3];
    uint16_t ingredientnr, gotlastingredient;
    uint32_t gotlastmeal, mealindex;
    std::array<std::array<char, 20>, 40> units;
    std::array<MealIngredient, 410> ingredients;
};
struct MealElement { uint32_t ingr; float amount; };
static_assert(sizeof(MealIngredient) == 52 && sizeof(MealElement) == 8);
static_assert(sizeof(MealHeader) == 22136);
}
