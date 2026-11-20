#pragma once

#include <android/log.h>
#include <sys/system_properties.h>
#include <cstdarg>

// Read the explicit policy rather than a process-wide liblog minimum priority.
inline int VectorLogLevel() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("log.tag.HraELL", value);
    return value[0] == 'V' ? 2 : value[0] == 'D' ? 1 : 0;
}

inline bool VectorLogAllowed(int priority) {
    const int level = VectorLogLevel();
    return level == 2 || (level == 1 && priority >= ANDROID_LOG_INFO);
}

inline int VectorLogPrint(int priority, const char* tag, const char* format, ...) {
    if (!VectorLogAllowed(priority)) return 0;
    va_list args;
    va_start(args, format);
    const int result = __android_log_vprint(priority, tag, format, args);
    va_end(args);
    return result;
}
