// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <stdlib.h>
#include <stdio.h>
#include <iostream>
#include <limits.h>

#include <boost/utility.hpp>
#include <gtest/gtest.h>

#include "gutil/bits.h"
#include "util/bit-util.h"
#include "util/cpu-info.h"

#include "common/names.h"

namespace impala {

TEST(BitUtil, Ceil) {
  EXPECT_EQ(BitUtil::Ceil(0, 1), 0);
  EXPECT_EQ(BitUtil::Ceil(1, 1), 1);
  EXPECT_EQ(BitUtil::Ceil(1, 2), 1);
  EXPECT_EQ(BitUtil::Ceil(1, 8), 1);
  EXPECT_EQ(BitUtil::Ceil(7, 8), 1);
  EXPECT_EQ(BitUtil::Ceil(8, 8), 1);
  EXPECT_EQ(BitUtil::Ceil(9, 8), 2);
  EXPECT_EQ(BitUtil::Ceil(9, 9), 1);
  EXPECT_EQ(BitUtil::Ceil(10000000000, 10), 1000000000);
  EXPECT_EQ(BitUtil::Ceil(10, 10000000000), 1);
  EXPECT_EQ(BitUtil::Ceil(100000000000, 10000000000), 10);
}

TEST(BitUtil, RoundUp) {
  EXPECT_EQ(BitUtil::RoundUp(0, 1), 0);
  EXPECT_EQ(BitUtil::RoundUp(1, 1), 1);
  EXPECT_EQ(BitUtil::RoundUp(1, 2), 2);
  EXPECT_EQ(BitUtil::RoundUp(6, 2), 6);
  EXPECT_EQ(BitUtil::RoundUp(7, 3), 9);
  EXPECT_EQ(BitUtil::RoundUp(9, 9), 9);
  EXPECT_EQ(BitUtil::RoundUp(10000000001, 10), 10000000010);
  EXPECT_EQ(BitUtil::RoundUp(10, 10000000000), 10000000000);
  EXPECT_EQ(BitUtil::RoundUp(100000000000, 10000000000), 100000000000);
}

TEST(BitUtil, RoundDown) {
  EXPECT_EQ(BitUtil::RoundDown(0, 1), 0);
  EXPECT_EQ(BitUtil::RoundDown(1, 1), 1);
  EXPECT_EQ(BitUtil::RoundDown(1, 2), 0);
  EXPECT_EQ(BitUtil::RoundDown(6, 2), 6);
  EXPECT_EQ(BitUtil::RoundDown(7, 3), 6);
  EXPECT_EQ(BitUtil::RoundDown(9, 9), 9);
  EXPECT_EQ(BitUtil::RoundDown(10000000001, 10), 10000000000);
  EXPECT_EQ(BitUtil::RoundDown(10, 10000000000), 0);
  EXPECT_EQ(BitUtil::RoundDown(100000000000, 10000000000), 100000000000);
}

TEST(BitUtil, Popcount) {
  EXPECT_EQ(BitUtil::Popcount(BOOST_BINARY(0 1 0 1 0 1 0 1)), 4);
  EXPECT_EQ(BitUtil::PopcountNoHw(BOOST_BINARY(0 1 0 1 0 1 0 1)), 4);
  EXPECT_EQ(BitUtil::Popcount(BOOST_BINARY(1 1 1 1 0 1 0 1)), 6);
  EXPECT_EQ(BitUtil::PopcountNoHw(BOOST_BINARY(1 1 1 1 0 1 0 1)), 6);
  EXPECT_EQ(BitUtil::Popcount(BOOST_BINARY(1 1 1 1 1 1 1 1)), 8);
  EXPECT_EQ(BitUtil::PopcountNoHw(BOOST_BINARY(1 1 1 1 1 1 1 1)), 8);
  EXPECT_EQ(BitUtil::Popcount(0), 0);
  EXPECT_EQ(BitUtil::PopcountNoHw(0), 0);
}

TEST(BitUtil, TrailingBits) {
  EXPECT_EQ(BitUtil::TrailingBits(BOOST_BINARY(1 1 1 1 1 1 1 1), 0), 0);
  EXPECT_EQ(BitUtil::TrailingBits(BOOST_BINARY(1 1 1 1 1 1 1 1), 1), 1);
  EXPECT_EQ(BitUtil::TrailingBits(BOOST_BINARY(1 1 1 1 1 1 1 1), 64),
            BOOST_BINARY(1 1 1 1 1 1 1 1));
  EXPECT_EQ(BitUtil::TrailingBits(BOOST_BINARY(1 1 1 1 1 1 1 1), 100),
            BOOST_BINARY(1 1 1 1 1 1 1 1));
  EXPECT_EQ(BitUtil::TrailingBits(0, 1), 0);
  EXPECT_EQ(BitUtil::TrailingBits(0, 64), 0);
  EXPECT_EQ(BitUtil::TrailingBits(1LL << 63, 0), 0);
  EXPECT_EQ(BitUtil::TrailingBits(1LL << 63, 63), 0);
  EXPECT_EQ(BitUtil::TrailingBits(1LL << 63, 64), 1LL << 63);
}

TEST(BitUtil, ByteSwap) {
  EXPECT_EQ(BitUtil::ByteSwap(static_cast<uint32_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(static_cast<uint32_t>(0x11223344)), 0x44332211);

  EXPECT_EQ(BitUtil::ByteSwap(static_cast<int32_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(static_cast<int32_t>(0x11223344)), 0x44332211);

  EXPECT_EQ(BitUtil::ByteSwap(static_cast<uint64_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(
      static_cast<uint64_t>(0x1122334455667788)), 0x8877665544332211);

  EXPECT_EQ(BitUtil::ByteSwap(static_cast<int64_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(
      static_cast<int64_t>(0x1122334455667788)), 0x8877665544332211);

  EXPECT_EQ(BitUtil::ByteSwap(static_cast<int16_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(static_cast<int16_t>(0x1122)), 0x2211);

  EXPECT_EQ(BitUtil::ByteSwap(static_cast<uint16_t>(0)), 0);
  EXPECT_EQ(BitUtil::ByteSwap(static_cast<uint16_t>(0x1122)), 0x2211);
}

TEST(BitUtil, Log2) {
  // We use gutil's implementation in place of an older custom implementation in BitUtil.
  // We leave this test here to ensure no test coverage is lost.
  EXPECT_EQ(Bits::Log2CeilingNonZero64(1), 0);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(2), 1);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(3), 2);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(4), 2);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(5), 3);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(INT_MAX), 31);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(UINT_MAX), 32);
  EXPECT_EQ(Bits::Log2CeilingNonZero64(ULLONG_MAX), 64);
}

TEST(BitUtil, RoundUpToPowerOf2) {
  EXPECT_EQ(BitUtil::RoundUpToPowerOf2(7, 8), 8);
  EXPECT_EQ(BitUtil::RoundUpToPowerOf2(8, 8), 8);
  EXPECT_EQ(BitUtil::RoundUpToPowerOf2(9, 8), 16);
}

TEST(BitUtil, RoundDownToPowerOf2) {
  EXPECT_EQ(BitUtil::RoundDownToPowerOf2(7, 8), 0);
  EXPECT_EQ(BitUtil::RoundDownToPowerOf2(8, 8), 8);
  EXPECT_EQ(BitUtil::RoundDownToPowerOf2(9, 8), 8);
}

TEST(BitUtil, RoundUpDown) {
  EXPECT_EQ(BitUtil::RoundUpNumBytes(7), 1);
  EXPECT_EQ(BitUtil::RoundUpNumBytes(8), 1);
  EXPECT_EQ(BitUtil::RoundUpNumBytes(9), 2);
  EXPECT_EQ(BitUtil::RoundDownNumBytes(7), 0);
  EXPECT_EQ(BitUtil::RoundDownNumBytes(8), 1);
  EXPECT_EQ(BitUtil::RoundDownNumBytes(9), 1);

  EXPECT_EQ(BitUtil::RoundUpNumi32(31), 1);
  EXPECT_EQ(BitUtil::RoundUpNumi32(32), 1);
  EXPECT_EQ(BitUtil::RoundUpNumi32(33), 2);
  EXPECT_EQ(BitUtil::RoundDownNumi32(31), 0);
  EXPECT_EQ(BitUtil::RoundDownNumi32(32), 1);
  EXPECT_EQ(BitUtil::RoundDownNumi32(33), 1);

  EXPECT_EQ(BitUtil::RoundUpNumi64(63), 1);
  EXPECT_EQ(BitUtil::RoundUpNumi64(64), 1);
  EXPECT_EQ(BitUtil::RoundUpNumi64(65), 2);
  EXPECT_EQ(BitUtil::RoundDownNumi64(63), 0);
  EXPECT_EQ(BitUtil::RoundDownNumi64(64), 1);
  EXPECT_EQ(BitUtil::RoundDownNumi64(65), 1);
}

}

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  impala::CpuInfo::Init();
  return RUN_ALL_TESTS();
}
