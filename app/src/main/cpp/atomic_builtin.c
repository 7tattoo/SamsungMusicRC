typedef unsigned long long samsung_u64;

// Android bionic does not export Clang's outline atomic helpers to app DSOs.
// Keep the helper inside this DSO so arm64 devices can load Oboe after restart.
samsung_u64 __aarch64_ldadd8_acq_rel(samsung_u64 value, samsung_u64 *address) {
    return __atomic_fetch_add(address, value, __ATOMIC_ACQ_REL);
}
