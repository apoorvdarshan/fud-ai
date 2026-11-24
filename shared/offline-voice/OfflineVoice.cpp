#include "fud_offline_voice.h"

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

#if defined(__APPLE__) && TARGET_OS_SIMULATOR

// The upstream iOS framework is device-only. Settings and lifecycle tests can
// still run on a simulator without linking it or pretending inference works.
fud_voice_turn *fud_voice_create(void) { return nullptr; }
void fud_voice_cancel(fud_voice_turn *) {}
void fud_voice_destroy(fud_voice_turn *) {}
int fud_voice_reply(fud_voice_turn *, const char *, const char *, const char *, const char *, bool) { return 1; }

#else

#if defined(__APPLE__) && __has_include(<llama/mtmd-helper.h>)
#include <llama/mtmd-helper.h>
#else
#include "mtmd-helper.h"
#endif

#include <atomic>
#include <chrono>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

struct fud_voice_turn {
    std::atomic_bool cancelled{false};
};

fud_voice_turn *fud_voice_create(void) {
    return new (std::nothrow) fud_voice_turn{};
}

void fud_voice_cancel(fud_voice_turn *turn) {
    if (turn) turn->cancelled.store(true);
}

void fud_voice_destroy(fud_voice_turn *turn) {
    delete turn;
}

namespace {
constexpr int context_size = 1536;
constexpr int batch_size = 256;
constexpr int max_steps = 480;
constexpr size_t sample_rate = 16000;
constexpr size_t max_input = 12 * sample_rate;

std::mutex inference;
std::once_flag backend;

template <typename T, void (*Free)(T *)>
using owned = std::unique_ptr<T, decltype(Free)>;

struct cancellation {
    fud_voice_turn *turn;
    const std::chrono::steady_clock::time_point deadline =
        std::chrono::steady_clock::now() + std::chrono::seconds(90);
    bool stopped() const {
        return turn->cancelled.load() || std::chrono::steady_clock::now() >= deadline;
    }
    static bool abort(void *p) { return static_cast<cancellation *>(p)->stopped(); }
    static bool load(float, void *p) { return !abort(p); }
};

int reply(cancellation &cancel, const char *model_path, const char *projector_path,
          const char *input_path, const char *output_path, bool gpu) {
    std::unique_lock lock(inference, std::try_to_lock);
    if (!lock) return 3;
    if (cancel.stopped()) return 2;

    // No raw prompts, transcripts or private temp file names in system logs.
    std::call_once(backend, [] {
        const auto mute = [](ggml_log_level, const char *, void *) {};
        llama_log_set(mute, nullptr);
        mtmd_helper_log_set(mute, nullptr);
        llama_backend_init();
    });

    auto params = llama_model_default_params();
    params.n_gpu_layers = gpu ? 99 : 0;
    params.progress_callback = cancellation::load;
    params.progress_callback_user_data = &cancel;
    owned<llama_model, llama_model_free> model(llama_model_load_from_file(model_path, params), llama_model_free);
    if (!model || cancel.stopped()) return 1;

    auto cp = llama_context_default_params();
    cp.n_ctx = context_size;
    cp.n_batch = batch_size;
    cp.n_ubatch = batch_size;
    cp.n_threads = cp.n_threads_batch = 4;
    cp.embeddings = true;
    cp.abort_callback = cancellation::abort;
    cp.abort_callback_data = &cancel;
    owned<llama_context, llama_free> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx || cancel.stopped()) return 1;

    auto mp = mtmd_context_params_default();
    mp.use_gpu = gpu;
    mp.n_threads = 4;
    mp.print_timings = false;
    mp.warmup = false;
    owned<mtmd_context, mtmd_free> media(mtmd_init_from_file(projector_path, model.get(), mp), mtmd_free);
    if (!media || cancel.stopped()) return 1;
    const auto caps = mtmd_helper_get_capabilities(model.get(), media.get());
    if (!caps.audio || !caps.audio_gen || mtmd_get_audio_sample_rate(media.get()) != sample_rate) return 1;

    // Input is only the app's own short, private WAV. The helper resamples to the encoder rate.
    auto source = mtmd_helper_bitmap_init_from_file(media.get(), input_path, false, mtmd_helper_init_opt_default());
    owned<mtmd_bitmap, mtmd_bitmap_free> audio(source.bitmap, mtmd_bitmap_free);
    if (!audio || !mtmd_bitmap_is_audio(audio.get())) return 4;
    const size_t samples = mtmd_bitmap_get_n_bytes(audio.get()) / sizeof(float);
    if (samples < sample_rate / 2 || samples > max_input) return 4;

    // LFM3-Audio's pinned chat template, with the media marker replaced by audio embeddings.
    // Never concatenate any user-controlled text into the template.
    const std::string prefix = std::string("<|im_start|>system\nRespond with interleaved text and audio. "
        "Reply briefly in English. You cannot access or change the user's data. "
        "Do not give medical advice; say when you are unsure."
        "<|im_end|>\n<|im_start|>user\n") + mtmd_default_marker();
    const mtmd_input_text prompt{prefix.data(), prefix.size(), true, true};
    owned<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
    const mtmd_bitmap *inputs[]{audio.get()};
    if (!chunks || mtmd_tokenize(media.get(), chunks.get(), &prompt, inputs, 1) != 0 ||
        mtmd_helper_get_n_pos(chunks.get()) + max_steps + 24 > context_size || cancel.stopped()) return 1;
    llama_pos used = 0;
    if (mtmd_helper_eval_chunks(media.get(), ctx.get(), chunks.get(), 0, 0, batch_size, false, &used) != 0 ||
        used <= 0 || cancel.stopped()) return 1;

    owned<mtmd_helper_gen_audio, mtmd_helper_gen_audio_free> gen(
        mtmd_helper_gen_audio_init(ctx.get(), media.get()), mtmd_helper_gen_audio_free);
    if (!gen) return 1;
    auto settings = mtmd_helper_gen_audio_inp_default();
    settings.prompt = "<|im_end|>\n<|im_start|>assistant\n";
    settings.prompt_len = std::char_traits<char>::length(settings.prompt);
    settings.n_past = used;
    settings.out_mode = MTMD_HELPER_GEN_AUDIO_OUT_MIXED;
    settings.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_WAV;
    if (mtmd_helper_gen_audio_set_input(gen.get(), &settings) != 0) return 1;
    int left;
    do {
        if (cancel.stopped()) return 2;
        left = mtmd_helper_gen_audio_step_prompt(gen.get(), batch_size);
    } while (left > 0);
    if (left < 0) return 1;

    const float *embedding = llama_get_embeddings_ith(ctx.get(), -1);
    if (!embedding) return 1;
    const int width = llama_model_n_embd(model.get());
    std::vector<float> hidden(embedding, embedding + width);
    owned<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) return 1;
    for (int step = 0; step < max_steps; ++step) {
        if (cancel.stopped()) return 2;
        bool stop = false;
        const float *next = nullptr;
        const llama_token token = mtmd_helper_gen_audio_next_is_text(gen.get())
            ? llama_sampler_sample(sampler.get(), ctx.get(), -1) : LLAMA_TOKEN_NULL;
        if (mtmd_helper_gen_audio_step_gen(gen.get(), token, hidden.data(), &next, &stop) != 0) return 1;
        if (stop || !next || llama_memory_seq_pos_max(llama_get_memory(ctx.get()), 0) + 2 >= context_size) break;
        hidden.assign(next, next + width);
    }
    const char *wav = nullptr;
    size_t length = 0;
    int64_t frames = 0;
    int32_t rate = 0;
    if (mtmd_helper_gen_audio_flush(gen.get()) != 0 ||
        mtmd_helper_gen_audio_get_output(gen.get(), &rate, &wav, &length, &frames) != 0 ||
        rate != 24000 || !wav || length <= 44 || frames < rate / 10) return 1;
    if (cancel.stopped()) return 2;

    FILE *file = std::fopen(output_path, "wb");
    if (!file) return 1;
    const bool written = std::fwrite(wav, 1, length, file) == length;
    const bool closed = std::fclose(file) == 0;
    if (!written || !closed) {
        std::remove(output_path);
        return 1;
    }
    return 0;
}
} // namespace

int fud_voice_reply(fud_voice_turn *turn, const char *model, const char *projector,
                    const char *input, const char *output, bool gpu) {
    if (!turn || !model || !projector || !input || !output) return 1;
    cancellation cancel{turn};
    try {
        const int result = reply(cancel, model, projector, input, output, gpu);
        return cancel.stopped() ? 2 : result;
    } catch (...) {
        return cancel.stopped() ? 2 : 1;
    }
}

#endif
