# Does Supertonic drop the last word at high speeds? Compare: cut at predicted duration (current) vs. extra room + silence trim.
import os, sys, re, numpy as np, onnxruntime as ort, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__))
from helper import load_cfgs, load_text_processor, load_voice_style, get_latent_mask
A, OUT = sys.argv[1], sys.argv[2]; os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, 'tail.txt'), 'w')
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); log.write(s + '\n'); log.flush()
onnx = os.path.join(A, 'onnx'); cfg = load_cfgs(onnx); tp = load_text_processor(onnx)
o = ort.SessionOptions(); o.intra_op_num_threads = 4
S = lambda n: ort.InferenceSession(os.path.join(onnx, n), sess_options=o, providers=['CPUExecutionProvider'])
dp, enc, est, voc = S('duration_predictor.onnx'), S('text_encoder.onnx'), S('vector_estimator.onnx'), S('vocoder.onnx')
sr = cfg['ae']['sample_rate']; chunk = cfg['ae']['base_chunk_size'] * cfg['ttl']['chunk_compress_factor']; ldim = cfg['ttl']['latent_dim'] * cfg['ttl']['chunk_compress_factor']
st = load_voice_style([os.path.join(A, 'voice_styles', 'M5.json')])
def synth(text, speed, mode, steps=5):
    ids, mask = tp([text], ['en'])
    d = dp.run(None, {'text_ids': ids, 'style_dp': st.dp, 'text_mask': mask})[0] / speed
    d = float(np.ravel(d)[0])
    if mode == 'room': d = d * 1.08 + 0.25
    emb = enc.run(None, {'text_ids': ids, 'style_ttl': st.ttl, 'text_mask': mask})[0]
    wl = int(d * sr); L = (wl + chunk - 1) // chunk
    x = np.random.randn(1, ldim, L).astype(np.float32); lm = np.ones((1, 1, L), np.float32)
    for k in range(steps):
        x = est.run(None, {'noisy_latent': x, 'text_emb': emb, 'style_ttl': st.ttl, 'text_mask': mask, 'latent_mask': lm,
                           'current_step': np.array([k], np.float32), 'total_step': np.array([steps], np.float32)})[0]
    w = voc.run(None, {'latent': x})[0][0][:wl]
    if mode == 'room':   # trim trailing quiet, keep 80 ms
        a = np.abs(w); thr = max(0.01, a.max() * 0.03); win = int(0.02 * sr)
        e = np.convolve(a, np.ones(win) / win, 'same'); idx = np.where(e > thr)[0]
        if len(idx): w = w[:min(len(w), idx[-1] + int(0.08 * sr))]
    return w
from faster_whisper import WhisperModel
wm = WhisperModel('small', device='cpu', compute_type='int8')
TEXTS = ["Another critical theme is the mechanics of totalitarian control.",
         "This involved not only brute force and terror, exemplified by the SS and Gestapo, but also the control of information.",
         "The regime moved quickly to silence its opponents and consolidate its power."]
for t in TEXTS:
    last = re.findall(r"[a-z]+", t.lower())[-1]
    P('\n' + t)
    for sp in (1.0, 1.2, 1.37, 1.6):
        for mode in ('cut', 'room'):
            ok = 0; heard = ''
            for seed in range(4):
                np.random.seed(seed); w = synth(t, sp, mode)
                f = os.path.join(OUT, f'tail_{TEXTS.index(t)}_{sp}_{mode}_{seed}.wav'); sf.write(f, w, sr)
                h = ' '.join(s.text for s in wm.transcribe(f, language='en', beam_size=5)[0]).strip()
                hw = re.findall(r"[a-z]+", h.lower()); ok += bool(hw) and hw[-1] == last; heard = h
                if seed: os.remove(f)
            P(f'  speed {sp:4} {mode:4}: last word "{last}" heard {ok}/4   e.g. {heard[-60:]!r}')
