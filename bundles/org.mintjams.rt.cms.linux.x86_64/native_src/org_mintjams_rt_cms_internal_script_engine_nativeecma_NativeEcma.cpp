#include <string.h>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <future>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <libplatform/libplatform.h>
#include <v8.h>

#include <org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.h>

namespace {

struct EvalResult {
  // 正常時: utf8 に結果（JSの最終値が文字列の場合）
  // 非文字列/undefined/null: utf8 = std::nullopt
  // 例外時: error にメッセージ
  std::optional<std::string> utf8;
  std::optional<std::string> error;
};

struct Job {
  std::vector<std::u16string> sources;
  std::promise<EvalResult> result;
};

class Worker {
public:
  Worker() : alloc_(v8::ArrayBuffer::Allocator::NewDefaultAllocator()) {
    v8::Isolate::CreateParams p;
    p.array_buffer_allocator = alloc_.get();
    isolate_ = v8::Isolate::New(p);
    th_ = std::thread([this]{ run(); });
  }
  ~Worker() { stop(); }

  void post(std::shared_ptr<Job> job) {
    { std::lock_guard<std::mutex> lk(mu_); q_.push_back(std::move(job)); }
    cv_.notify_one();
  }

  void stop() {
    if (stopping_.exchange(true)) return;
    cv_.notify_all();
    if (th_.joinable()) th_.join();
    if (isolate_) { isolate_->Dispose(); isolate_ = nullptr; }
  }

private:
  void run() {
    v8::Isolate::Scope iso_scope(isolate_);
    isolate_->SetMicrotasksPolicy(v8::MicrotasksPolicy::kAuto);

    while (!stopping_) {
      std::shared_ptr<Job> job;
      {
        std::unique_lock<std::mutex> lk(mu_);
        cv_.wait(lk, [&]{ return stopping_ || !q_.empty(); });
        if (stopping_) break;
        job = std::move(q_.front()); q_.pop_front();
      }

      EvalResult er;
      {
        v8::HandleScope hs(isolate_);
        v8::Local<v8::Context> ctx = v8::Context::New(isolate_);
        v8::Context::Scope cs(ctx);
        v8::TryCatch tc(isolate_);

        v8::Local<v8::Value> last;
        bool ok = true;

        for (auto& s16 : job->sources) {
          v8::HandleScope hs2(isolate_);
          v8::Local<v8::String> source =
            v8::String::NewFromTwoByte(isolate_,
              reinterpret_cast<const uint16_t*>(s16.data()),
              v8::NewStringType::kNormal,
              static_cast<int>(s16.size())).ToLocalChecked();

          v8::ScriptOrigin origin(
            v8::String::NewFromUtf8(isolate, ("<eval:" + std::to_string(i) + ">").c_str()).ToLocalChecked());
          v8::Local<v8::Script> script;
          if (!v8::Script::Compile(ctx, source, &origin).ToLocal(&script)) { ok = false; break; }
          if (!script->Run(ctx).ToLocal(&last)) { ok = false; break; }
        }

        isolate_->PerformMicrotaskCheckpoint();

        if (!ok || tc.HasCaught()) {
          v8::String::Utf8Value exc(isolate, tc.Exception());
          v8::Local<v8::Message> msg = tc.Message();
          std::string out = *exc ? *exc : "JavaScript exception";

          if (!msg.IsEmpty()) {
            v8::String::Utf8Value fname(isolate, msg->GetScriptOrigin().ResourceName());
            int line = msg->GetLineNumber(ctx).FromMaybe(0);
            int col  = msg->GetStartColumn(ctx).FromMaybe(0) + 1;
            out = (std::string(*fname ? *fname : "<unknown>") + ":" +
                   std::to_string(line) + ":" + std::to_string(col) + ": " + out);

            // スタックトレース（あれば）
            v8::Local<v8::Value> st;
            if (tc.StackTrace(ctx).ToLocal(&st) && st->IsString()) {
              v8::String::Utf8Value st_utf8(isolate, st.As<v8::String>());
              out += "\n" + std::string(*st_utf8 ? *st_utf8 : "");
            }
          }
          // ここで Java 例外へ
          jclass exClass = env->FindClass("javax/script/ScriptException");
          if (!exClass) exClass = env->FindClass("java/lang/RuntimeException");
          env->ThrowNew(exClass, out.c_str());
          // 結果は使わないので return/null
        } else {
          if (!last.IsEmpty() && last->IsString()) {
            v8::String::Utf8Value utf8(isolate_, last);
            er.utf8 = std::string(*utf8 ? *utf8 : "");
          } // 非文字列/undefined/null は utf8 = nullopt のまま
        }
      }

      job->result.set_value(std::move(er));
    }
  }

  std::unique_ptr<v8::ArrayBuffer::Allocator> alloc_;
  v8::Isolate* isolate_{nullptr};
  std::thread th_;
  std::mutex mu_;
  std::condition_variable cv_;
  std::deque<std::shared_ptr<Job>> q_;
  std::atomic<bool> stopping_{false};
};

class Engine {
public:
  void init(const char* exePath, int poolSize) {
    if (initialized_) return;
//    v8::V8::InitializeICUDefaultLocation(exePath);
//    v8::V8::InitializeExternalStartupData(exePath);
    platform_ = v8::platform::NewDefaultPlatform();
    v8::V8::InitializePlatform(platform_.get());
    v8::V8::Initialize();

    if (poolSize <= 0) {
      unsigned hc = std::thread::hardware_concurrency();
      poolSize = hc > 0 ? (int)hc : 2;
    }
    for (int i = 0; i < poolSize; ++i) {
      workers_.push_back(std::make_unique<Worker>());
    }
    initialized_ = true;
  }

  void shutdown() {
    if (!initialized_) return;
    workers_.clear();
    v8::V8::Dispose();
    v8::V8::DisposePlatform();
    platform_.reset();
    initialized_ = false;
  }

  // JNIスレッド上で呼ばれ、ブロッキングで結果を返す
  EvalResult evalUTF8(JNIEnv* env, jobjectArray _sources) {
    EvalResult er;
    if (!initialized_ || workers_.empty()) return er;

    auto job = std::make_shared<Job>();
    jsize n = env->GetArrayLength(_sources);
    job->sources.reserve((size_t)n);
    for (jsize i = 0; i < n; ++i) {
      jstring js = (jstring)env->GetObjectArrayElement(_sources, i);
      const jchar* chars = env->GetStringChars(js, nullptr);
      jsize len = env->GetStringLength(js);
      job->sources.emplace_back(
        reinterpret_cast<const char16_t*>(chars),
        reinterpret_cast<const char16_t*>(chars) + len);
      env->ReleaseStringChars(js, chars);
      env->DeleteLocalRef(js);
    }

    auto fut = job->result.get_future();
    size_t idx = rr_.fetch_add(1) % workers_.size();
    workers_[idx]->post(job);
    er = fut.get();
    return er;
  }

private:
  std::unique_ptr<v8::Platform> platform_;
  std::vector<std::unique_ptr<Worker>> workers_;
  std::atomic<size_t> rr_{0};
  bool initialized_{false};
};

static std::unique_ptr<Engine> g_engine;

} // namespace

// ===== JNI ====

jint JNI_OnLoad(JavaVM* vm, void* reserved) { return JNI_VERSION_1_6; }
void JNI_OnUnload(JavaVM* vm, void* reserved) {}

JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeLoad
  (JNIEnv *env, jobject _this, jstring _executablePath, jint _poolSize) {
  const char* exe = env->GetStringUTFChars(_executablePath, nullptr);
  if (!g_engine) g_engine = std::make_unique<Engine>();
  g_engine->init(exe, (int)_poolSize);
  env->ReleaseStringUTFChars(_executablePath, exe);
}

JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeUnload
  (JNIEnv *env, jobject _this) {
  if (g_engine) { g_engine->shutdown(); g_engine.reset(); }
}

JNIEXPORT jstring JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeEval
  (JNIEnv *env, jobject _this, jobjectArray _sources) {
  if (!g_engine) return nullptr;
  EvalResult er = g_engine->evalUTF8(env, _sources);

  if (er.error.has_value()) {
    jclass exClass = env->FindClass("javax/script/ScriptException");
    if (!exClass) exClass = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(exClass, er.error->c_str());
    return nullptr;
  }

  if (er.utf8.has_value()) {
    return env->NewStringUTF(er.utf8->c_str());
  }
  return nullptr;
}
