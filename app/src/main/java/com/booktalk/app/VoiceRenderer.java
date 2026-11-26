package com.booktalk.app;

/** Anything that can turn text into audio for BookTalk to play or save (phone voices, AI voices). */
interface VoiceRenderer {
    PhoneRenderer.Pcm render(String text, float pitch, float rate, String voice) throws Exception;
}
