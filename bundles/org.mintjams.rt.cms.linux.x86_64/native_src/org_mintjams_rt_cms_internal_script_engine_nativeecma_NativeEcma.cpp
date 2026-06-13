#include <string.h>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <future>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <optional>
#include <unordered_map>
#include <chrono>
#include <cstdio>

#include <libplatform/libplatform.h>
#include <v8.h>

#include <org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.h>

namespace
{

	struct EvalResult
	{
		// 正常時: utf8 に結果（JSの最終値が文字列の場合）
		// 非文字列/undefined/null: utf8 = std::nullopt
		// 例外時: error にメッセージ
		std::optional<std::string> utf8;
		std::optional<std::string> error;
	};

	struct Job
	{
		std::vector<std::u16string> sources;
		std::promise<EvalResult> result;
	};

	class Worker
	{
	public:
		Worker() : alloc_(v8::ArrayBuffer::Allocator::NewDefaultAllocator())
		{
			v8::Isolate::CreateParams p;
			p.array_buffer_allocator = alloc_.get();
			isolate_ = v8::Isolate::New(p);

			{
				v8::Locker locker(isolate_);
				v8::Isolate::Scope iso_scope(isolate_);
				v8::HandleScope hs(isolate_);

				v8::Local<v8::ObjectTemplate> g = v8::ObjectTemplate::New(isolate_);

				// 例）ホスト関数を生やしたい場合（任意）
				// g->Set(v8::String::NewFromUtf8(isolate_, "print").ToLocalChecked(),
				//        v8::FunctionTemplate::New(isolate_, &Worker::Print));

				global_tpl_.Reset(isolate_, g);
			}

			th_ = std::thread([this]
							  { run(); });
		}
		~Worker() { stop(); }

		void post(std::shared_ptr<Job> job)
		{
			{
				std::lock_guard<std::mutex> lk(mu_);
				q_.push_back(std::move(job));
			}
			cv_.notify_one();
		}

		void stop()
		{
			if (stopping_.exchange(true))
				return;

			cv_.notify_all();
			if (th_.joinable())
				th_.join();

			if (!global_tpl_.IsEmpty())
				global_tpl_.Reset();

			code_cache_.clear();

			// Isolate（ワークスペース単位の実行資源）のみを破棄する。
			// V8 プラットフォーム自体はプロセスグローバルなので、ここでは触れない。
			if (isolate_)
			{
				isolate_->Dispose();
				isolate_ = nullptr;
			}
			alloc_.reset();
		}

	private:
		void run()
		{
			v8::Locker locker(isolate_);
			v8::Isolate::Scope iso_scope(isolate_);
			isolate_->SetMicrotasksPolicy(v8::MicrotasksPolicy::kAuto);

			while (!stopping_)
			{
				std::shared_ptr<Job> job;
				{
					std::unique_lock<std::mutex> lk(mu_);
					cv_.wait(lk, [&]
							 { return stopping_ || !q_.empty(); });
					if (stopping_)
						break;
					job = std::move(q_.front());
					q_.pop_front();
				}

				auto jobStart = std::chrono::steady_clock::now();
				// std::fprintf(stderr, "[Worker] Job start\n");

				EvalResult er;
				{
					v8::HandleScope hs(isolate_);

					v8::Local<v8::ObjectTemplate> g = global_tpl_.Get(isolate_);
					v8::Local<v8::Context> ctx = v8::Context::New(isolate_, nullptr, g);
					v8::Context::Scope cs(ctx);

					v8::TryCatch tc(isolate_);
					tc.SetCaptureMessage(true);

					v8::Local<v8::Value> last;
					bool ok = true;

					int idx = 0;
					for (auto &s16 : job->sources)
					{
						v8::HandleScope hs2(isolate_);
						// Source
						v8::Local<v8::String> src =
							v8::String::NewFromTwoByte(isolate_,
													   reinterpret_cast<const uint16_t *>(s16.data()),
													   v8::NewStringType::kNormal,
													   static_cast<int>(s16.size()))
								.ToLocalChecked();

						// Resource name
						std::string name = std::string("<eval:") + std::to_string(idx) + ">";
						v8::Local<v8::String> resName =
							v8::String::NewFromUtf8(isolate_, name.c_str()).ToLocalChecked();
						v8::ScriptOrigin origin(resName);

						// ★ キャッシュ検索
						uint64_t key = fnv1a64_utf8(s16);
						std::unique_ptr<v8::ScriptCompiler::CachedData> cached;
						auto it = code_cache_.find(key);
						// std::fprintf(stderr, "[Worker] Cache %s\n", (it != code_cache_.end()) ? "hit" : "miss");
						if (it != code_cache_.end())
						{
							// V8に所有権を渡すので、newでコピーを作る
							const auto &blob = it->second;
							auto *cd = new v8::ScriptCompiler::CachedData(
								blob.data(),
								static_cast<int>(blob.size()),
								v8::ScriptCompiler::CachedData::BufferPolicy::BufferNotOwned);
							cached.reset(cd);
						}

						// Source 作成（cached があればそれ付き）
						v8::ScriptCompiler::Source source(src, origin, cached.release());

						// Unbound でコンパイル（キャッシュ消費 or 通常）
						v8::Local<v8::UnboundScript> unbound;
						v8::ScriptCompiler::CompileOptions opt =
							(it != code_cache_.end())
								? v8::ScriptCompiler::kConsumeCodeCache
								: v8::ScriptCompiler::kEagerCompile;

						auto compileStart = std::chrono::steady_clock::now();
						// std::fprintf(stderr, "[Worker] Compile start\n");
						bool compiled = v8::ScriptCompiler::CompileUnboundScript(isolate_, &source, opt)
											.ToLocal(&unbound);
						auto compileEnd = std::chrono::steady_clock::now();
						// std::fprintf(stderr, "[Worker] Compile end (%lld ms)\n", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(compileEnd - compileStart).count());
						if (!compiled)
						{
							ok = false;
							break;
						}

						// ★ キャッシュが無かったときは作って保存
						if (it == code_cache_.end())
						{
							if (const v8::ScriptCompiler::CachedData *cd = v8::ScriptCompiler::CreateCodeCache(unbound))
							{
								code_cache_[key].assign(cd->data, cd->data + cd->length);
							}
						}
						else
						{
							// もし食わせたキャッシュが古くて拒否されたら（V8バージョン違い等）
							// source.GetCachedData()->rejected を見るAPIがある版もあります。
							// その場合は拒否時に新規作成して差し替える処理を入れてOK。
						}

						// 実行
						v8::Local<v8::Script> script = unbound->BindToCurrentContext();
						auto execStart = std::chrono::steady_clock::now();
						// std::fprintf(stderr, "[Worker] Execute start\n");
						bool ran = script->Run(ctx).ToLocal(&last);
						auto execEnd = std::chrono::steady_clock::now();
						// std::fprintf(stderr, "[Worker] Execute end (%lld ms)\n", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(execEnd - execStart).count());
						if (!ran)
						{
							ok = false;
							break;
						}
						++idx;
					}

					isolate_->PerformMicrotaskCheckpoint();

					if (tc.HasCaught())
					{
						std::string out = "JavaScript exception";
						// 例外メッセージ本体
						if (!tc.Exception().IsEmpty())
						{
							v8::String::Utf8Value exc(isolate_, tc.Exception());
							if (*exc)
								out = *exc;
						}
						// 位置情報（あれば）
						v8::Local<v8::Message> msg = tc.Message();
						if (!msg.IsEmpty())
						{
							v8::String::Utf8Value fname(isolate_, msg->GetScriptOrigin().ResourceName());
							int line = msg->GetLineNumber(ctx).FromMaybe(0);
							int col = msg->GetStartColumn(ctx).FromMaybe(0) + 1;
							std::string fn = (*fname && std::string(*fname).size()) ? *fname : "<unknown>";
							out = fn + ":" + std::to_string(line) + ":" + std::to_string(col) + ": " + out;
						}
						// ★スタックトレースは「本当に例外がある時だけ」& おとなしく
						v8::Local<v8::Value> st;
						if (tc.StackTrace(ctx).ToLocal(&st) && st->IsString())
						{
							v8::String::Utf8Value st_utf8(isolate_, st.As<v8::String>());
							if (*st_utf8)
							{
								std::string s = *st_utf8;
								if (s.size() > 8192)
									s.resize(8192); // 無限再帰対策で上限
								out += "\n" + s;
							}
						}
						er.error = out;
					}
					else if (!ok)
					{
						// 例外は無いが Compile/Run が false を返しただけのケース
						er.error = "Script failed (compile/run)";
					}
					else
					{
						if (!last.IsEmpty() && last->IsString())
						{
							v8::String::Utf8Value utf8(isolate_, last);
							er.utf8 = std::string(*utf8 ? *utf8 : "");
						}
					}
				}

				job->result.set_value(std::move(er));
				auto jobEnd = std::chrono::steady_clock::now();
				// std::fprintf(stderr, "[Worker] Job end (%lld ms)\n", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(jobEnd - jobStart).count());
			}
		}

		// 簡易FNV-1a 64bit
		static uint64_t fnv1a64_utf8(const std::u16string &s)
		{
			uint64_t h = 1469598103934665603ull;
			for (char16_t c : s)
			{
				// UTF-8化せず、16bit値をそのまま2バイトとして混ぜる（速い＆衝突少）
				uint8_t b0 = static_cast<uint8_t>(c & 0xFF);
				uint8_t b1 = static_cast<uint8_t>((c >> 8) & 0xFF);
				h ^= b0;
				h *= 1099511628211ull;
				h ^= b1;
				h *= 1099511628211ull;
			}
			return h;
		}

		// key=FNV64, value=CachedDataの生バイト
		std::unordered_map<uint64_t, std::vector<uint8_t>> code_cache_;

		std::unique_ptr<v8::ArrayBuffer::Allocator> alloc_;
		v8::Isolate *isolate_{nullptr};
		std::thread th_;
		std::mutex mu_;
		std::condition_variable cv_;
		std::deque<std::shared_ptr<Job>> q_;
		std::atomic<bool> stopping_{false};
		v8::Global<v8::ObjectTemplate> global_tpl_;
	};

	// ============================================================================
	// V8 プラットフォーム（プロセスグローバル・一度だけ初期化・破棄しない）
	//
	// V8 のプラットフォーム寿命（InitializePlatform/Initialize/Dispose/DisposePlatform）
	// はプロセス内で一度きり・不可逆である。特に DisposePlatform を呼ぶと起動状態が
	// kPlatformDisposed に遷移し、以降の InitializePlatform は
	//   Check failed: current_state != V8StartupState::kPlatformDisposed
	// で abort してプロセスごと停止する。
	//
	// ワークスペースの追加/削除のたびにプラットフォームを初期化/破棄してはならない。
	// ここでは std::call_once で一度だけ初期化し、プロセス終了まで破棄しない
	// （プロセス終了時の解放は OS に委ねる：V8 の設計上これが安全）。
	// ============================================================================
	std::once_flag g_platform_once;
	std::unique_ptr<v8::Platform> g_platform;

	void ensurePlatformInitialized()
	{
		std::call_once(g_platform_once, []
					   {
			//    v8::V8::InitializeICUDefaultLocation(exePath);
			//    v8::V8::InitializeExternalStartupData(exePath);
			g_platform = v8::platform::NewDefaultPlatform();
			v8::V8::InitializePlatform(g_platform.get());
			v8::V8::Initialize(); });
	}

	// ============================================================================
	// IsolatePool（ワークスペース単位の実行資源）
	//
	// 1 ワークスペース = 1 プール。プールは Worker（=Isolate＋専用スレッド）の集合。
	// ワークスペース削除時はこのプールだけを破棄し、プラットフォームには触れない。
	// ============================================================================
	class IsolatePool
	{
	public:
		explicit IsolatePool(int poolSize)
		{
			if (poolSize <= 0)
			{
				unsigned hc = std::thread::hardware_concurrency();
				poolSize = hc > 0 ? (int)hc : 2;
			}
			for (int i = 0; i < poolSize; ++i)
			{
				workers_.push_back(std::make_unique<Worker>());
			}
		}

		// デストラクタで全 Worker を停止・join し、各 Isolate を破棄する。
		// 実行中のジョブがあれば join が完了を待つため、安全に解放できる。
		~IsolatePool() { workers_.clear(); }

		// 呼び出しスレッド上でブロッキングして結果を返す
		EvalResult evalUTF8(JNIEnv *env, jobjectArray _sources)
		{
			EvalResult er;
			if (workers_.empty())
			{
				er.error = "Native ECMA isolate pool has no workers.";
				return er;
			}

			auto job = std::make_shared<Job>();
			jsize n = env->GetArrayLength(_sources);
			job->sources.reserve((size_t)n);
			for (jsize i = 0; i < n; ++i)
			{
				jstring js = (jstring)env->GetObjectArrayElement(_sources, i);
				const jchar *chars = env->GetStringChars(js, nullptr);
				jsize len = env->GetStringLength(js);
				job->sources.emplace_back(
					reinterpret_cast<const char16_t *>(chars),
					reinterpret_cast<const char16_t *>(chars) + len);
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
		std::vector<std::unique_ptr<Worker>> workers_;
		std::atomic<size_t> rr_{0};
	};

	// ============================================================================
	// プール・レジストリ（ハンドル <-> IsolatePool の対応）
	//
	// nativeCreatePool が返す jlong ハンドルでプールを参照する。
	// eval はレジストリから shared_ptr をコピーして保持してから実行するため、
	// 実行中に別スレッドが nativeDestroyPool でレジストリから外しても、
	// 実行が終わるまでプールは生存する（use-after-free を防止）。
	// ============================================================================
	std::mutex g_pools_mu;
	std::unordered_map<uint64_t, std::shared_ptr<IsolatePool>> g_pools;
	std::atomic<uint64_t> g_next_handle{1};

	std::shared_ptr<IsolatePool> findPool(uint64_t handle)
	{
		std::lock_guard<std::mutex> lk(g_pools_mu);
		auto it = g_pools.find(handle);
		if (it == g_pools.end())
			return nullptr;
		return it->second;
	}

} // namespace

// ===== JNI ====

jint JNI_OnLoad(JavaVM *vm, void *reserved) { return JNI_VERSION_1_6; }
void JNI_OnUnload(JavaVM *vm, void *reserved) {}

// V8 プラットフォームをプロセスで一度だけ初期化する（static / 冪等）。
JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeInitPlatform(JNIEnv *env, jclass _clazz, jstring _directoryPath)
{
	// _directoryPath は将来の ICU/外部スタートアップデータ初期化のために受け取る
	// （現在のビルドフラグでは未使用）。
	(void)_directoryPath;
	ensurePlatformInitialized();
}

// ワークスペース単位の Isolate プールを生成し、ハンドルを返す。
JNIEXPORT jlong JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeCreatePool(JNIEnv *env, jobject _this, jint _poolSize)
{
	ensurePlatformInitialized();

	auto pool = std::make_shared<IsolatePool>((int)_poolSize);
	uint64_t handle = g_next_handle.fetch_add(1);
	{
		std::lock_guard<std::mutex> lk(g_pools_mu);
		g_pools.emplace(handle, std::move(pool));
	}
	return (jlong)handle;
}

// ワークスペース単位の Isolate プールだけを破棄する（プラットフォームは破棄しない）。
JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeDestroyPool(JNIEnv *env, jobject _this, jlong _handle)
{
	std::shared_ptr<IsolatePool> pool;
	{
		std::lock_guard<std::mutex> lk(g_pools_mu);
		auto it = g_pools.find((uint64_t)_handle);
		if (it == g_pools.end())
			return;
		pool = std::move(it->second);
		g_pools.erase(it);
	}
	// レジストリのロック外でプールを破棄する（Worker の join がブロックし得るため）。
	// 実行中の eval が同じ shared_ptr を保持していれば、その完了まで生存する。
	pool.reset();
}

JNIEXPORT jstring JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeEval(JNIEnv *env, jobject _this, jlong _handle, jobjectArray _sources)
{
	std::shared_ptr<IsolatePool> pool = findPool((uint64_t)_handle);
	if (!pool)
	{
		jclass exClass = env->FindClass("javax/script/ScriptException");
		if (!exClass)
			exClass = env->FindClass("java/lang/RuntimeException");
		env->ThrowNew(exClass, "Native ECMA isolate pool is not available.");
		return nullptr;
	}

	EvalResult er = pool->evalUTF8(env, _sources);

	if (er.error.has_value())
	{
		jclass exClass = env->FindClass("javax/script/ScriptException");
		if (!exClass)
			exClass = env->FindClass("java/lang/RuntimeException");
		env->ThrowNew(exClass, er.error->c_str());
		return nullptr;
	}

	if (er.utf8.has_value())
	{
		return env->NewStringUTF(er.utf8->c_str());
	}
	return nullptr;
}
