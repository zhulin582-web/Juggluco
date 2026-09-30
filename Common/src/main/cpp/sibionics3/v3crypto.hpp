#pragma once
#include "v3protocol.hpp"
#include "../bcrypt/tinycrypt/aes.h"

namespace gs3v3 {
// TinyCrypt is already compiled into libg; no new shared library is needed.
inline bool encryptBlock(const Block &key, const Block &in, Block &out) {
    tc_aes_key_sched_struct schedule{};
    return tc_aes128_set_encrypt_key(&schedule,key.data())==1 &&
           tc_aes_encrypt(out.data(),in.data(),&schedule)==1;
}
}
