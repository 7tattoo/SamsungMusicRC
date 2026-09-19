// Android bionic (until very recent versions) does NOT export Clang's arm64
// outline-atomic helpers to app DSOs. Oboe's decoder/stream code is compiled
// without -mno-outline-atomics (it lives in the oboe lib before our own flags),
// so its __atomic_* operations may lower to calls like __aarch64_cas8_acq_rel.
// If those are left undefined, dlopen of libsamsung_oboe.so fails with:
//   cannot locate symbol "__aarch64_cas8_acq_rel" referenced by ".../libsamsung_oboe.so"
// and AAudio / OpenSL ES output silently stops working.
//
// Keep every helper Clang/LLVM can emit for arm64 IN THIS DSO so the native
// sink stays self-contained and loads on any device/ROM. All implementations
// use the compiler's __atomic builtins which inline ldaxp/stlxp loops (no
// recursion back into the outline helpers).
//
// ABI (LLVM specified atomics): each op takes a scalar value + object pointer.
// - ldadd/ldclr/ldset/swp:  T op(N)(T value, T *object), returns the OLD value.
// - cas:                    int cas{N}_{ord}(T *expected, T desired, T *object),
//                           returns 1 on success and stores the observed value
//                           into *expected on failure.

#include <stdint.h>

#define ATOMIC_SIZE_1 uint8_t
#define ATOMIC_SIZE_2 uint16_t
#define ATOMIC_SIZE_4 uint32_t
#define ATOMIC_SIZE_8 uint64_t

// Memory-order mapping for the __atomic builtins.
#define ORD_RELAXED __ATOMIC_RELAXED
#define ORD_ACQUIRE __ATOMIC_ACQUIRE
#define ORD_RELEASE __ATOMIC_RELEASE
#define ORD_ACQ_REL __ATOMIC_ACQ_REL

// ldadd / ldclr / ldset / swp — return the previous value, mirroring the
// outline-atomic caller contract exactly.
#define DEFINE_OP(OP_NAME, __atomic_fn, UNOP)                                                       \
    ATOMIC_SIZE_8 __aarch64_##OP_NAME##8_acquire(ATOMIC_SIZE_8 v, ATOMIC_SIZE_8 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQUIRE); } \
    ATOMIC_SIZE_8 __aarch64_##OP_NAME##8_release(ATOMIC_SIZE_8 v, ATOMIC_SIZE_8 *p) { return __atomic_fn(p, (UNOP v), ORD_RELEASE); } \
    ATOMIC_SIZE_8 __aarch64_##OP_NAME##8_acq_rel(ATOMIC_SIZE_8 v, ATOMIC_SIZE_8 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQ_REL); } \
    ATOMIC_SIZE_8 __aarch64_##OP_NAME##8_relaxed(ATOMIC_SIZE_8 v, ATOMIC_SIZE_8 *p) { return __atomic_fn(p, (UNOP v), ORD_RELAXED); } \
    ATOMIC_SIZE_4 __aarch64_##OP_NAME##4_acquire(ATOMIC_SIZE_4 v, ATOMIC_SIZE_4 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQUIRE); } \
    ATOMIC_SIZE_4 __aarch64_##OP_NAME##4_release(ATOMIC_SIZE_4 v, ATOMIC_SIZE_4 *p) { return __atomic_fn(p, (UNOP v), ORD_RELEASE); } \
    ATOMIC_SIZE_4 __aarch64_##OP_NAME##4_acq_rel(ATOMIC_SIZE_4 v, ATOMIC_SIZE_4 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQ_REL); } \
    ATOMIC_SIZE_4 __aarch64_##OP_NAME##4_relaxed(ATOMIC_SIZE_4 v, ATOMIC_SIZE_4 *p) { return __atomic_fn(p, (UNOP v), ORD_RELAXED); } \
    ATOMIC_SIZE_2 __aarch64_##OP_NAME##2_acquire(ATOMIC_SIZE_2 v, ATOMIC_SIZE_2 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQUIRE); } \
    ATOMIC_SIZE_2 __aarch64_##OP_NAME##2_release(ATOMIC_SIZE_2 v, ATOMIC_SIZE_2 *p) { return __atomic_fn(p, (UNOP v), ORD_RELEASE); } \
    ATOMIC_SIZE_2 __aarch64_##OP_NAME##2_acq_rel(ATOMIC_SIZE_2 v, ATOMIC_SIZE_2 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQ_REL); } \
    ATOMIC_SIZE_2 __aarch64_##OP_NAME##2_relaxed(ATOMIC_SIZE_2 v, ATOMIC_SIZE_2 *p) { return __atomic_fn(p, (UNOP v), ORD_RELAXED); } \
    ATOMIC_SIZE_1 __aarch64_##OP_NAME##1_acquire(ATOMIC_SIZE_1 v, ATOMIC_SIZE_1 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQUIRE); } \
    ATOMIC_SIZE_1 __aarch64_##OP_NAME##1_release(ATOMIC_SIZE_1 v, ATOMIC_SIZE_1 *p) { return __atomic_fn(p, (UNOP v), ORD_RELEASE); } \
    ATOMIC_SIZE_1 __aarch64_##OP_NAME##1_acq_rel(ATOMIC_SIZE_1 v, ATOMIC_SIZE_1 *p) { return __atomic_fn(p, (UNOP v), ORD_ACQ_REL); } \
    ATOMIC_SIZE_1 __aarch64_##OP_NAME##1_relaxed(ATOMIC_SIZE_1 v, ATOMIC_SIZE_1 *p) { return __atomic_fn(p, (UNOP v), ORD_RELAXED); }

DEFINE_OP(ldadd, __atomic_fetch_add, )
DEFINE_OP(ldclr, __atomic_fetch_and, ~)
DEFINE_OP(ldset, __atomic_fetch_or, )
DEFINE_OP(swp, __atomic_exchange_n, )

// cas: *expected is an in/out parameter updated to the observed value on miss.
#define DEFINE_CAS(N, T)                                                                                          \
    int __aarch64_cas##N##_relaxed(T *expected, T desired, T *object) {                                           \
        return __atomic_compare_exchange_n(object, expected, desired, /*weak=*/0, ORD_RELAXED, ORD_RELAXED);      \
    }                                                                                                             \
    int __aarch64_cas##N##_acquire(T *expected, T desired, T *object) {                                           \
        return __atomic_compare_exchange_n(object, expected, desired, 0, ORD_ACQUIRE, ORD_ACQUIRE);               \
    }                                                                                                             \
    int __aarch64_cas##N##_release(T *expected, T desired, T *object) {                                           \
        return __atomic_compare_exchange_n(object, expected, desired, 0, ORD_RELEASE, ORD_RELAXED);               \
    }                                                                                                             \
    int __aarch64_cas##N##_acq_rel(T *expected, T desired, T *object) {                                           \
        return __atomic_compare_exchange_n(object, expected, desired, 0, ORD_ACQ_REL, ORD_ACQUIRE);               \
    }

DEFINE_CAS(1, uint8_t)
DEFINE_CAS(2, uint16_t)
DEFINE_CAS(4, uint32_t)
DEFINE_CAS(8, uint64_t)