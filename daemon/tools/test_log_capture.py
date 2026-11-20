"""Host regression test for the actual native collector (no Android SDK/device needed).

Run with Python 3 and g++ (C++23) on PATH. Android/JNI calls are stubbed; collector,
policy checks, descriptor rotation and writes are compiled from production sources.
"""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
STUBS = {
    "android/log.h": r"""
#pragma once
#include <cstdarg>
#include <cstddef>
enum android_LogPriority { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT,
 ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN,
 ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
enum log_id_t { LOG_ID_MAIN, LOG_ID_CRASH };
inline int emitted = 0;
inline int __android_log_vprint(int, const char*, const char*, va_list) { return ++emitted; }
""",
    "sys/system_properties.h": r"""
#pragma once
#include <cstring>
#define PROP_VALUE_MAX 92
inline char policy_value[PROP_VALUE_MAX] = "";
inline int __system_property_get(const char*, char* value) {
 std::strcpy(value, policy_value); return std::strlen(value);
}
inline int __system_property_set(const char*, const char*) { return 0; }
""",
    "sys/uio.h": r"""
#pragma once
#include <unistd.h>
struct iovec { void* iov_base; size_t iov_len; };
inline ssize_t writev(int fd, const iovec* parts, int count) {
 ssize_t total = 0;
 for (int i=0; i<count; ++i) {
  auto n = write(fd, parts[i].iov_base, parts[i].iov_len);
  if (n < 0) return n;
  total += n;
 }
 return total;
}
""",
    "jni.h": r"""
#pragma once
#include <cstdio>
#include <unistd.h>
using jobject = void*;
using jmethodID = void*;
using jclass = void*;
using jint = int;
using jboolean = bool;
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE true
#define JNI_FALSE false
inline int file_opens = 0;
inline void (*clear_fds)() = nullptr;
struct JNIEnv {
 int CallIntMethod(jobject, jmethodID, bool) {
  auto* file = tmpfile();
  int fd = dup(fileno(file));
  fclose(file);
  ++file_opens;
  return fd;
 }
 jclass GetObjectClass(jobject) { return nullptr; }
 jmethodID GetMethodID(jclass, const char*, const char*) { return reinterpret_cast<void*>(1); }
 void CallVoidMethod(jobject, jmethodID) { if (clear_fds) clear_fds(); }
 void DeleteLocalRef(jclass) {}
};
""",
    "utils/jni_helper.hpp": r"""
#pragma once
#include <jni.h>
namespace lsplant {
inline jclass JNI_GetObjectClass(JNIEnv*, jobject) { return nullptr; }
inline jmethodID JNI_GetMethodID(JNIEnv*, jclass, const char*, const char*) {
 return reinterpret_cast<void*>(1);
}
}
""",
}
HARNESS = r"""
#include <algorithm>
#include <array>
#include <string_view>
#include <thread>
#include <mutex>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <unistd.h>
#ifdef _WIN32
inline tm* localtime_r(const time_t* value, tm* result) {
 return localtime_s(result, value) == 0 ? result : nullptr;
}
#endif
int closed_count = 0;
int tracked_close(int fd) { ++closed_count; return close(fd); }
#define close tracked_close
#define private public
#include "daemon/src/main/jni/logcat.cpp"
#undef private
#undef close

AndroidLogEntry next_entry{};
extern "C" {
long android_logger_get_log_size(logger*) { return 0; }
int android_logger_set_log_size(logger*, unsigned long) { return 0; }
logger_list* android_logger_list_alloc(int, unsigned int, pid_t) { return nullptr; }
void android_logger_list_free(logger_list*) {}
int android_logger_list_read(logger_list*, log_msg*) { return -1; }
logger* android_logger_open(logger_list*, log_id_t) { return nullptr; }
int android_log_processLogBuffer(logger_entry*, AndroidLogEntry* entry) {
 *entry = next_entry; return 0;
}
}
int main() {
 // The native output gate defaults Off, including fatal/error messages.
 for (const char* value : {"", "S", "D", "V"}) {
  std::strcpy(policy_value, value);
  int expected_level = value[0]=='V' ? 2 : value[0]=='D' ? 1 : 0;
  assert(VectorLogLevel() == expected_level);
  for (int priority=ANDROID_LOG_VERBOSE; priority<=ANDROID_LOG_FATAL; ++priority) {
   int before = emitted;
   VectorLogPrint(priority, "VectorTest", "test");
   bool expected = expected_level==2 || (expected_level==1 && priority>=ANDROID_LOG_INFO);
   assert(emitted-before == int(expected));
  }
 }
 JNIEnv env;
 Logcat collector(&env, nullptr, nullptr);
 active_logcat = &collector;
 log_msg message{};
 message.entry.lid = LOG_ID_MAIN;
 auto send = [&](int priority, const char* tag) {
  next_entry.priority = static_cast<android_LogPriority>(priority);
  next_entry.tag = tag; next_entry.tagLen = std::strlen(tag)+1;
  next_entry.message = "line"; next_entry.messageLen = 4;
  next_entry.pid = getpid(); next_entry.tv_sec = 1;
  collector.ProcessBuffer(&message);
 };
 auto set = [&](int level) {
  Java_org_matrix_vector_daemon_env_LogcatMonitor_configureNative(&env, nullptr, level);
 };
 set(0);
 send(ANDROID_LOG_ERROR, "VectorContext");
 assert(file_opens==0 && collector.modules_fd_==-1 && collector.verbose_fd_==-1);
 set(1);
 send(ANDROID_LOG_INFO, "VectorDaemon");
 assert(file_opens==2 && collector.verbose_written_>0 && collector.modules_written_==0);
 auto basic_size = collector.verbose_written_;
 send(ANDROID_LOG_DEBUG, "VectorDaemon");
 send(ANDROID_LOG_INFO, "VectorContext");
 assert(collector.verbose_written_==basic_size && collector.modules_written_==0);
 send(ANDROID_LOG_INFO, "VectorModuleManager");
 assert(collector.modules_written_>0); // Loading results belong to Basic.
 auto module_size = collector.modules_written_;
 send(ANDROID_LOG_ERROR, "VectorContext");
 assert(collector.modules_written_>module_size);
 set(2);
 send(ANDROID_LOG_INFO, "VectorContext");
 assert(collector.modules_written_>0);
 auto detailed_size = collector.verbose_written_;
 send(ANDROID_LOG_DEBUG, "VectorDaemon");
 assert(collector.verbose_written_>detailed_size);
 int closed_before = closed_count;
 set(0);
 assert(collector.modules_fd_==-1 && collector.verbose_fd_==-1);
 assert(closed_count == closed_before+2);
 int opens_before = file_opens;
 Java_org_matrix_vector_daemon_env_LogcatMonitor_refreshNative(&env, nullptr, true);
 send(ANDROID_LOG_FATAL, "VectorContext");
 assert(file_opens==opens_before && collector.modules_written_==0 && collector.verbose_written_==0);
 set(1);
 send(ANDROID_LOG_WARN, "VectorContext");
 assert(collector.modules_written_>0 && file_opens==opens_before+2);
 puts("PASS: native output gate, Basic/Detailed filters, Off closes files, refresh stays silent, re-enable works");
}
"""

with tempfile.TemporaryDirectory(prefix="hraell-log-test-") as temp:
    work = pathlib.Path(temp)
    for name, content in STUBS.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    harness = work / "capture_test.cpp"
    harness.write_text(HARNESS, encoding="utf-8")
    binary = work / "capture_test.exe"
    subprocess.run(["g++", "-std=c++23", "-pthread", "-I", str(work), "-I", str(ROOT),
                    "-I", str(ROOT / "native/include"), str(harness), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
