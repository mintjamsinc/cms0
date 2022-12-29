#include <string.h>
#include <libplatform/libplatform.h>
#include <v8.h>
#include <org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.h>

class NativeEngine {
private:
	std::unique_ptr<v8::Platform> platform = nullptr;

	v8::Local<v8::String> toV8String(JNIEnv *env, v8::Isolate *isolate, jstring &string) {
		const uint16_t *unicodeString = env->GetStringChars(string, nullptr);
		int length = env->GetStringLength(string);
		v8::MaybeLocal<v8::String> twoByteString = v8::String::NewFromTwoByte(isolate, unicodeString, v8::NewStringType::kNormal, length);
		if (twoByteString.IsEmpty()) {
			return v8::Local<v8::String>();
		}
		v8::Local<v8::String> result = twoByteString.ToLocalChecked();
		env->ReleaseStringChars(string, unicodeString);
		return result;
	}

	std::string toString(JNIEnv *env, v8::Isolate *isolate, jstring &str) {
		v8::Local<v8::String> v8Str = toV8String(env, isolate, str);
		v8::String::Utf8Value stdString(isolate, v8Str);
		return toCString(stdString);
	}

	const char* toCString(const v8::String::Utf8Value& value) {
		return *value ? *value : "<string conversion failed>";
	}

public:
	NativeEngine() {
	}

	~NativeEngine() {
		// Tear down V8.
		v8::V8::Dispose();
		v8::V8::DisposePlatform();
	}

	void init(JNIEnv *env, jstring _executablePath) {
		const char* executablePath = env->GetStringUTFChars(_executablePath, 0);
		{
			// Initialize V8.
//			v8::V8::InitializeICU();
			v8::V8::InitializeICUDefaultLocation(executablePath);
			v8::V8::InitializeExternalStartupData(executablePath);
			platform = v8::platform::NewDefaultPlatform();
			v8::V8::InitializePlatform(platform.get());
			v8::V8::Initialize();
		}
		env->ReleaseStringUTFChars(_executablePath, executablePath);
	}

	jstring eval(JNIEnv *env, jobjectArray _sources) {
		jstring resultValue = NULL;

		// Create a new Isolate and make it the current one.
		v8::Isolate::CreateParams create_params;
		create_params.array_buffer_allocator = v8::ArrayBuffer::Allocator::NewDefaultAllocator();
		v8::Isolate *isolate = v8::Isolate::New(create_params);
		{
			v8::Isolate::Scope isolate_scope(isolate);
			// Create a stack-allocated handle scope.
			v8::HandleScope handle_scope(isolate);
			// Create a new context.
			v8::Local<v8::Context> context = v8::Context::New(isolate);
			// Enter the context for compiling and running the hello world script.
			v8::Context::Scope context_scope(context);
			{
				// Create a TryCatch.
				v8::TryCatch tryCatch(isolate);
				// Evaluate the scripts.
				v8::Local<v8::Value> result;
				for (int i = 0; i < env->GetArrayLength(_sources); i++) {
					jstring _source = (jstring) env->GetObjectArrayElement(_sources, i);
					// Create a string containing the JavaScript source code.
					v8::Local<v8::String> source = toV8String(env, isolate, _source);

					// Compile the source code.
					v8::Local<v8::Script> script;
					if (!v8::Script::Compile(context, source).ToLocal(&script)) {
						break;
					}

					// Run the script to get the result.
					if (!script->Run(context).ToLocal(&result)) {
						break;
					}
				}

				if (!tryCatch.HasCaught()) {
					// Convert the result to an UTF8 string.
					if (result->IsNull() || result->IsUndefined()) {
						resultValue = NULL;
					} else if (result->IsString()) {
						v8::String::Utf8Value utf8(isolate, result);
						resultValue = env->NewStringUTF(*utf8);
					} else {
						resultValue = NULL;
					}
				}

				if (tryCatch.HasCaught()) {
					// Throw the exception.
					resultValue = NULL;
					v8::String::Utf8Value exception(isolate, tryCatch.Exception());
					const char* exceptionString = toCString(exception);
					v8::Local<v8::Message> message = tryCatch.Message();
					jclass _class = env->FindClass("javax/script/ScriptException");
					if (message.IsEmpty()) {
						env->ThrowNew(_class, exceptionString);
					} else {
						// (filename):(line number):(message).
						v8::String::Utf8Value filename(isolate, message->GetScriptOrigin().ResourceName());
						v8::Local<v8::Context> context(isolate->GetCurrentContext());
						const char *filenameString = toCString(filename);
						int linenum = message->GetLineNumber(context).FromJust();
						std::string buf = "";
						buf.append((std::string(filenameString).compare("undefined") == 0) ? "<undefined>" : filenameString);
						buf.append(":");
						buf.append(std::to_string(linenum));
						buf.append(":");
						buf.append(exceptionString);
						env->ThrowNew(_class, buf.c_str());
					}
				}
			}
		}

		// Dispose the isolate and tear down V8.
		isolate->Dispose();
		delete create_params.array_buffer_allocator;

		return resultValue;
	}
};

NativeEngine* nativeEngine = nullptr;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
	return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM *vm, void *reserved) {
}

JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeLoad(JNIEnv *env, jobject _this, jstring _executablePath) {
	nativeEngine = new NativeEngine();
	nativeEngine->init(env, _executablePath);
}

JNIEXPORT void JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeUnload(JNIEnv *env, jobject _this) {
	if (nativeEngine) {
		delete nativeEngine;
		nativeEngine = nullptr;
	}
}

JNIEXPORT jstring JNICALL Java_org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma_nativeEval(JNIEnv *env, jobject _this, jobjectArray _sources) {
	return nativeEngine->eval(env, _sources);
}
