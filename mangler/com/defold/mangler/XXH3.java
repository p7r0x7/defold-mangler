package com.defold.mangler;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java port of XXH3's 64-bit variant ({@code XXH3_64bits_withSeed}),
 * ported directly from the reference implementation in xxHash's
 * {@code xxhash.h} (Yann Collet, BSD 2-Clause). See https://xxhash.com and
 * https://github.com/Cyan4973/xxHash.
 *
 * Only the scalar code paths are ported (no SIMD), which is sufficient for
 * bit-exact compatibility with the reference algorithm -- the scalar paths
 * are what every xxHash binding falls back to and they produce identical
 * output to the vectorized ones.
 *
 * This class is self-contained (no native dependency) so the extension does
 * not need to ship a JNI binding into bob's plugin classpath.
 */
final class XXH3 {

    private XXH3() {}

    // ---- constants, verbatim from xxhash.h ------------------------------------

    private static final long PRIME32_1 = 0x9E3779B1L;
    private static final long PRIME32_2 = 0x85EBCA77L;
    private static final long PRIME32_3 = 0xC2B2AE3DL;

    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private static final int SECRET_DEFAULT_SIZE = 192;
    private static final int SECRET_SIZE_MIN = 136;
    private static final int STRIPE_LEN = 64;
    private static final int SECRET_CONSUME_RATE = 8;
    private static final int SECRET_MERGEACCS_START = 11;
    private static final int SECRET_LASTACC_START = 7;
    private static final int MIDSIZE_MAX = 240;
    private static final int MIDSIZE_STARTOFFSET = 3;
    private static final int MIDSIZE_LASTOFFSET = 17;

    private static final byte[] K_SECRET = {
        (byte)0xb8, (byte)0xfe, (byte)0x6c, (byte)0x39, (byte)0x23, (byte)0xa4, (byte)0x4b, (byte)0xbe,
        (byte)0x7c, (byte)0x01, (byte)0x81, (byte)0x2c, (byte)0xf7, (byte)0x21, (byte)0xad, (byte)0x1c,
        (byte)0xde, (byte)0xd4, (byte)0x6d, (byte)0xe9, (byte)0x83, (byte)0x90, (byte)0x97, (byte)0xdb,
        (byte)0x72, (byte)0x40, (byte)0xa4, (byte)0xa4, (byte)0xb7, (byte)0xb3, (byte)0x67, (byte)0x1f,
        (byte)0xcb, (byte)0x79, (byte)0xe6, (byte)0x4e, (byte)0xcc, (byte)0xc0, (byte)0xe5, (byte)0x78,
        (byte)0x82, (byte)0x5a, (byte)0xd0, (byte)0x7d, (byte)0xcc, (byte)0xff, (byte)0x72, (byte)0x21,
        (byte)0xb8, (byte)0x08, (byte)0x46, (byte)0x74, (byte)0xf7, (byte)0x43, (byte)0x24, (byte)0x8e,
        (byte)0xe0, (byte)0x35, (byte)0x90, (byte)0xe6, (byte)0x81, (byte)0x3a, (byte)0x26, (byte)0x4c,
        (byte)0x3c, (byte)0x28, (byte)0x52, (byte)0xbb, (byte)0x91, (byte)0xc3, (byte)0x00, (byte)0xcb,
        (byte)0x88, (byte)0xd0, (byte)0x65, (byte)0x8b, (byte)0x1b, (byte)0x53, (byte)0x2e, (byte)0xa3,
        (byte)0x71, (byte)0x64, (byte)0x48, (byte)0x97, (byte)0xa2, (byte)0x0d, (byte)0xf9, (byte)0x4e,
        (byte)0x38, (byte)0x19, (byte)0xef, (byte)0x46, (byte)0xa9, (byte)0xde, (byte)0xac, (byte)0xd8,
        (byte)0xa8, (byte)0xfa, (byte)0x76, (byte)0x3f, (byte)0xe3, (byte)0x9c, (byte)0x34, (byte)0x3f,
        (byte)0xf9, (byte)0xdc, (byte)0xbb, (byte)0xc7, (byte)0xc7, (byte)0x0b, (byte)0x4f, (byte)0x1d,
        (byte)0x8a, (byte)0x51, (byte)0xe0, (byte)0x4b, (byte)0xcd, (byte)0xb4, (byte)0x59, (byte)0x31,
        (byte)0xc8, (byte)0x9f, (byte)0x7e, (byte)0xc9, (byte)0xd9, (byte)0x78, (byte)0x73, (byte)0x64,
        (byte)0xea, (byte)0xc5, (byte)0xac, (byte)0x83, (byte)0x34, (byte)0xd3, (byte)0xeb, (byte)0xc3,
        (byte)0xc5, (byte)0x81, (byte)0xa0, (byte)0xff, (byte)0xfa, (byte)0x13, (byte)0x63, (byte)0xeb,
        (byte)0x17, (byte)0x0d, (byte)0xdd, (byte)0x51, (byte)0xb7, (byte)0xf0, (byte)0xda, (byte)0x49,
        (byte)0xd3, (byte)0x16, (byte)0x55, (byte)0x26, (byte)0x29, (byte)0xd4, (byte)0x68, (byte)0x9e,
        (byte)0x2b, (byte)0x16, (byte)0xbe, (byte)0x58, (byte)0x7d, (byte)0x47, (byte)0xa1, (byte)0xfc,
        (byte)0x8f, (byte)0xf8, (byte)0xb8, (byte)0xd1, (byte)0x7a, (byte)0xd0, (byte)0x31, (byte)0xce,
        (byte)0x45, (byte)0xcb, (byte)0x3a, (byte)0x8f, (byte)0x95, (byte)0x16, (byte)0x04, (byte)0x28,
        (byte)0xaf, (byte)0xd7, (byte)0xfb, (byte)0xca, (byte)0xbb, (byte)0x4b, (byte)0x40, (byte)0x7e
    };

    // ---- public API -------------------------------------------------------------

    /** Convenience: hashes the UTF-8 encoding of {@code s} with the given 64-bit seed. */
    public static long hash64(String s, long seed) {
        return xxh3_64bits_withSeed(s.getBytes(StandardCharsets.UTF_8), seed);
    }

    public static long xxh3_64bits_withSeed(byte[] input, long seed) {
        int len = input.length;
        if (len <= 16) return len_0to16_64b(input, 0, len, K_SECRET, seed);
        if (len <= 128) return len_17to128_64b(input, 0, len, K_SECRET, seed);
        if (len <= MIDSIZE_MAX) return len_129to240_64b(input, 0, len, K_SECRET, seed);
        return hashLong_64b_withSeed(input, len, seed);
    }

    // ---- bit-level helpers (verbatim semantics of the C macros) ----------------

    private static long readLE64(byte[] b, int off) {
        return (b[off] & 0xFFL)
            | (b[off + 1] & 0xFFL) << 8
            | (b[off + 2] & 0xFFL) << 16
            | (b[off + 3] & 0xFFL) << 24
            | (b[off + 4] & 0xFFL) << 32
            | (b[off + 5] & 0xFFL) << 40
            | (b[off + 6] & 0xFFL) << 48
            | (b[off + 7] & 0xFFL) << 56;
    }

    private static long readLE32(byte[] b, int off) {
        return (b[off] & 0xFFL)
            | (b[off + 1] & 0xFFL) << 8
            | (b[off + 2] & 0xFFL) << 16
            | (b[off + 3] & 0xFFL) << 24;
    }

    private static void writeLE64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    private static long xorshift64(long v, int shift) { return v ^ (v >>> shift); }

    private static long swap32(long x32) {
        int v = (int) x32;
        return Integer.toUnsignedLong(Integer.reverseBytes(v));
    }

    private static long swap64(long x) { return Long.reverseBytes(x); }

    private static long mult32to64(long x, long y) { return (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL); }

    /** 64x64->128 unsigned multiply, folded by xor of the two halves (XXH3_mul128_fold64). */
    private static long mul128Fold64(long lhs, long rhs) {
        long lo_lo = mult32to64(lhs, rhs);
        long hi_lo = mult32to64(lhs >>> 32, rhs);
        long lo_hi = mult32to64(lhs, rhs >>> 32);
        long hi_hi = mult32to64(lhs >>> 32, rhs >>> 32);

        long cross = (lo_lo >>> 32) + (hi_lo & 0xFFFFFFFFL) + lo_hi;
        long upper = (hi_lo >>> 32) + (cross >>> 32) + hi_hi;
        long lower = (cross << 32) | (lo_lo & 0xFFFFFFFFL);
        return lower ^ upper;
    }

    private static long avalanche64(long h64) {
        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;
        return h64;
    }

    /** XXH3_avalanche (fast variant used for already-mixed accumulators). */
    private static long avalancheFast(long h64) {
        h64 = xorshift64(h64, 37);
        h64 *= 0x165667919E3779F9L;
        h64 = xorshift64(h64, 32);
        return h64;
    }

    private static long rrmxmx(long h64, long len) {
        h64 ^= Long.rotateLeft(h64, 49) ^ Long.rotateLeft(h64, 24);
        h64 *= 0x9FB21C651E98DF25L;
        h64 ^= (h64 >>> 35) + len;
        h64 *= 0x9FB21C651E98DF25L;
        return xorshift64(h64, 28);
    }

    private static long mix16B(byte[] input, int inOff, byte[] secret, int secOff, long seed) {
        long input_lo = readLE64(input, inOff);
        long input_hi = readLE64(input, inOff + 8);
        return mul128Fold64(
            input_lo ^ (readLE64(secret, secOff) + seed),
            input_hi ^ (readLE64(secret, secOff + 8) - seed));
    }

    // ---- short inputs (0..16) ---------------------------------------------------

    private static long len_1to3_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        int c1 = input[off] & 0xFF;
        int c2 = input[off + (len >> 1)] & 0xFF;
        int c3 = input[off + len - 1] & 0xFF;
        long combined = ((long) c1 << 16) | ((long) c2 << 24) | ((long) c3) | ((long) len << 8);
        long bitflip = (readLE32(secret, 0) ^ readLE32(secret, 4)) + seed;
        long keyed = (combined & 0xFFFFFFFFL) ^ bitflip;
        return avalanche64(keyed);
    }

    private static long len_4to8_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        seed ^= (swap32(seed & 0xFFFFFFFFL) << 32);
        long input1 = readLE32(input, off);
        long input2 = readLE32(input, off + len - 4);
        long bitflip = (readLE64(secret, 8) ^ readLE64(secret, 16)) - seed;
        long input64 = input2 + (input1 << 32);
        long keyed = input64 ^ bitflip;
        return rrmxmx(keyed, len);
    }

    private static long len_9to16_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        long bitflip1 = (readLE64(secret, 24) ^ readLE64(secret, 32)) + seed;
        long bitflip2 = (readLE64(secret, 40) ^ readLE64(secret, 48)) - seed;
        long input_lo = readLE64(input, off) ^ bitflip1;
        long input_hi = readLE64(input, off + len - 8) ^ bitflip2;
        long acc = len + swap64(input_lo) + input_hi + mul128Fold64(input_lo, input_hi);
        return avalanche64(acc);
    }

    private static long len_0to16_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        if (len > 8) return len_9to16_64b(input, off, len, secret, seed);
        if (len >= 4) return len_4to8_64b(input, off, len, secret, seed);
        if (len > 0) return len_1to3_64b(input, off, len, secret, seed);
        return avalanche64(seed ^ (readLE64(secret, 56) ^ readLE64(secret, 64)));
    }

    // ---- mid-size inputs (17..240) ----------------------------------------------

    private static long len_17to128_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        long acc = len * PRIME64_1;
        if (len > 32) {
            if (len > 64) {
                if (len > 96) {
                    acc += mix16B(input, off + 48, secret, 96, seed);
                    acc += mix16B(input, off + len - 64, secret, 112, seed);
                }
                acc += mix16B(input, off + 32, secret, 64, seed);
                acc += mix16B(input, off + len - 48, secret, 80, seed);
            }
            acc += mix16B(input, off + 16, secret, 32, seed);
            acc += mix16B(input, off + len - 32, secret, 48, seed);
        }
        acc += mix16B(input, off, secret, 0, seed);
        acc += mix16B(input, off + len - 16, secret, 16, seed);
        return avalancheFast(acc);
    }

    private static long len_129to240_64b(byte[] input, int off, int len, byte[] secret, long seed) {
        long acc = len * PRIME64_1;
        int nbRounds = len / 16;
        for (int i = 0; i < 8; i++) {
            acc += mix16B(input, off + 16 * i, secret, 16 * i, seed);
        }
        acc = avalancheFast(acc);
        for (int i = 8; i < nbRounds; i++) {
            acc += mix16B(input, off + 16 * i, secret, 16 * (i - 8) + MIDSIZE_STARTOFFSET, seed);
        }
        acc += mix16B(input, off + len - 16, secret, SECRET_SIZE_MIN - MIDSIZE_LASTOFFSET, seed);
        return avalancheFast(acc);
    }

    // ---- long inputs (>240): full accumulator path ------------------------------

    private static final long[] INIT_ACC = {
        PRIME32_3, PRIME64_1, PRIME64_2, PRIME64_3, PRIME64_4, PRIME32_2, PRIME64_5, PRIME32_1
    };

    private static void accumulate512(long[] acc, byte[] input, int inOff, byte[] secret, int secOff) {
        for (int i = 0; i < 8; i++) {
            long data_val = readLE64(input, inOff + 8 * i);
            long data_key = data_val ^ readLE64(secret, secOff + 8 * i);
            acc[i ^ 1] += data_val;
            acc[i] += mult32to64(data_key & 0xFFFFFFFFL, data_key >>> 32);
        }
    }

    private static void scrambleAcc(long[] acc, byte[] secret, int secOff) {
        for (int i = 0; i < 8; i++) {
            long key64 = readLE64(secret, secOff + 8 * i);
            long acc64 = acc[i];
            acc64 = xorshift64(acc64, 47);
            acc64 ^= key64;
            acc64 *= PRIME32_1;
            acc[i] = acc64;
        }
    }

    private static void accumulate(long[] acc, byte[] input, int inOff, byte[] secret, int secOff, int nbStripes) {
        for (int n = 0; n < nbStripes; n++) {
            accumulate512(acc, input, inOff + n * STRIPE_LEN, secret, secOff + n * SECRET_CONSUME_RATE);
        }
    }

    private static void hashLongInternalLoop(long[] acc, byte[] input, int len, byte[] secret) {
        int secretSize = secret.length;
        int nbStripesPerBlock = (secretSize - STRIPE_LEN) / SECRET_CONSUME_RATE;
        int block_len = STRIPE_LEN * nbStripesPerBlock;
        int nb_blocks = (len - 1) / block_len;

        for (int n = 0; n < nb_blocks; n++) {
            accumulate(acc, input, n * block_len, secret, 0, nbStripesPerBlock);
            scrambleAcc(acc, secret, secretSize - STRIPE_LEN);
        }

        int nbStripes = ((len - 1) - (block_len * nb_blocks)) / STRIPE_LEN;
        accumulate(acc, input, nb_blocks * block_len, secret, 0, nbStripes);

        int p = len - STRIPE_LEN;
        accumulate512(acc, input, p, secret, secretSize - STRIPE_LEN - SECRET_LASTACC_START);
    }

    private static long mix2Accs(long[] acc, int accOff, byte[] secret, int secOff) {
        return mul128Fold64(acc[accOff] ^ readLE64(secret, secOff), acc[accOff + 1] ^ readLE64(secret, secOff + 8));
    }

    private static long mergeAccs(long[] acc, byte[] secret, int secOff, long start) {
        long result = start;
        for (int i = 0; i < 4; i++) {
            result += mix2Accs(acc, 2 * i, secret, secOff + 16 * i);
        }
        return avalancheFast(result);
    }

    private static long hashLong64bInternal(byte[] input, int len, byte[] secret) {
        long[] acc = INIT_ACC.clone();
        hashLongInternalLoop(acc, input, len, secret);
        return mergeAccs(acc, secret, SECRET_MERGEACCS_START, (long) len * PRIME64_1);
    }

    private static byte[] initCustomSecret(long seed64) {
        byte[] customSecret = new byte[SECRET_DEFAULT_SIZE];
        int nbRounds = SECRET_DEFAULT_SIZE / 16;
        for (int i = 0; i < nbRounds; i++) {
            long lo = readLE64(K_SECRET, 16 * i) + seed64;
            long hi = readLE64(K_SECRET, 16 * i + 8) - seed64;
            writeLE64(customSecret, 16 * i, lo);
            writeLE64(customSecret, 16 * i + 8, hi);
        }
        return customSecret;
    }

    private static long hashLong_64b_withSeed(byte[] input, int len, long seed) {
        if (seed == 0) return hashLong64bInternal(input, len, K_SECRET);
        byte[] customSecret = initCustomSecret(seed);
        return hashLong64bInternal(input, len, customSecret);
    }
}
