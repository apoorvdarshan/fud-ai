#ifndef FUD_OFFLINE_VOICE_H
#define FUD_OFFLINE_VOICE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct fud_voice_turn fud_voice_turn;

fud_voice_turn *fud_voice_create(void);
void fud_voice_cancel(fud_voice_turn *turn);
void fud_voice_destroy(fud_voice_turn *turn);

// Blocking, private-file WAV in/out. 0 = success; 1 = failed; 2 = cancelled;
// 3 = busy; 4 = missing/short audio. Cancel may run on another thread;
// destroy only after inference and all cancel callers have returned.
int fud_voice_reply(fud_voice_turn *turn, const char *model, const char *projector,
                    const char *input, const char *output, bool gpu);

#ifdef __cplusplus
}
#endif
#endif
