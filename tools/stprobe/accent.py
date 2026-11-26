# Accent test: speak ENGLISH text but tag it as another language, so Supertonic uses that language's pronunciation.
# Checks with Whisper: is it still understandable English (word error rate), and which language does it sound like?
import os, sys, glob, re, json
import numpy as np, onnxruntime as ort, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__))
from helper import TextToSpeech, load_cfgs, load_text_processor, load_voice_style

A = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, 'accents.txt'), 'w')
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); log.write(s + '\n'); log.flush()
TEXT = "Martha had never trusted the sea. It took her father when she was nine, and it had been trying to take the lighthouse ever since. Is anyone there, she called into the dark."
LANGS = ['en', 'fr', 'de', 'es', 'it', 'nl', 'pt', 'pl', 'ru', 'sv', 'da', 'fi', 'hi', 'ar', 'tr', 'el', 'cs', 'ro', 'hu', 'vi', 'ja', 'ko']
onnx = os.path.join(A, 'onnx')
o = ort.SessionOptions(); o.intra_op_num_threads = 4
s = lambda n: ort.InferenceSession(os.path.join(onnx, n), sess_options=o, providers=['CPUExecutionProvider'])
tts = TextToSpeech(load_cfgs(onnx), load_text_processor(onnx), s('duration_predictor.onnx'), s('text_encoder.onnx'), s('vector_estimator.onnx'), s('vocoder.onnx'))
voices = {'M1': os.path.join(A, 'voice_styles', 'M1.json'), 'F1': os.path.join(A, 'voice_styles', 'F1.json')}

from faster_whisper import WhisperModel
wm = WhisperModel('small', device='cpu', compute_type='int8')
def words(t): return re.sub(r"[^a-z ]", ' ', t.lower()).split()
def wer(ref, hyp):
    r, h = words(ref), words(hyp); d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1])); prev, d[j] = d[j], cur
    return d[len(h)] / len(r)

P(f"{'voice':5} {'tag':4} {'WER':>5}  {'P(en)':>6}  top language guesses  | heard")
for vn, vp in voices.items():
    st = load_voice_style([vp])
    for lg in LANGS:
        try:
            w, d = tts(TEXT, lg, st, 8)
        except Exception as e:
            P(vn, lg, 'FAILED', e); continue
        f = os.path.join(OUT, f'accent_{vn}_{lg}.wav'); sf.write(f, w[0], tts.sample_rate)
        segs, info = wm.transcribe(f, language='en', beam_size=1)
        heard = ' '.join(x.text for x in segs).strip()
        _, info2 = wm.transcribe(f, beam_size=1)
        probs = sorted(info2.all_language_probs or [], key=lambda x: -x[1])[:3]
        pen = dict(info2.all_language_probs or []).get('en', 0)
        P(f"{vn:5} {lg:4} {wer(TEXT, heard):5.2f}  {pen:6.2f}  {', '.join(f'{a}:{b:.2f}' for a, b in probs):28s} | {heard[:90]}")
