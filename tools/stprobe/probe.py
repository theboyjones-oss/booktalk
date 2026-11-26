# One-off test of Supertonic 3 for BookTalk: speed on 2 CPU threads, int8 size/speed, voice samples, blending.
import json, os, sys, time, glob, subprocess
import numpy as np, onnxruntime as ort, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__))
from helper import TextToSpeech, load_cfgs, load_text_processor, load_voice_style, Style

A = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, 'results.txt'), 'w')
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); log.write(s + '\n'); log.flush()

TEXT = ("Martha had never trusted the sea. It took her father when she was nine, and it had been trying to take the lighthouse ever since. "
        "“Is anyone there?” she called into the dark. Nobody answered!")

def load(onnx_dir, threads=2):
    o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.inter_op_num_threads = 1
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    s = lambda n: ort.InferenceSession(os.path.join(onnx_dir, n), sess_options=o, providers=['CPUExecutionProvider'])
    return TextToSpeech(load_cfgs(onnx_dir), load_text_processor(onnx_dir), s('duration_predictor.onnx'), s('text_encoder.onnx'), s('vector_estimator.onnx'), s('vocoder.onnx'))

def bench(tts, style, steps, label, reps=3):
    tts(TEXT, 'en', style, steps)  # warm-up
    t0 = time.time(); dur = 0
    for _ in range(reps):
        wav, d = tts(TEXT, 'en', style, steps); dur += float(np.sum(d))
    el = time.time() - t0
    P(f'{label:32s} steps={steps}  audio={dur/reps:5.2f}s  time={el/reps:5.2f}s  RTF={el/dur:.3f}  ({dur/el:.1f}x real time)')
    return wav

onnx = os.path.join(A, 'onnx'); vs = sorted(glob.glob(os.path.join(A, 'voice_styles', '*.json')))
P('voices:', [os.path.basename(v)[:-5] for v in vs])
for f in sorted(glob.glob(os.path.join(onnx, '*'))): P(f'{os.path.basename(f):28s} {os.path.getsize(f)/1e6:8.1f} MB')
P('voice style file:', os.path.getsize(vs[0]) / 1e3, 'KB; dims', json.load(open(vs[0]))['style_ttl']['dims'], json.load(open(vs[0]))['style_dp']['dims'])

tts = load(onnx)
style = load_voice_style([vs[0]])
for st in (4, 5, 8): wav = bench(tts, style, st, 'fp32 2 threads')
tts1 = load(onnx, 1); bench(tts1, style, 5, 'fp32 1 thread')

# all voices, one sample file each (steps 8, fp32)
for v in vs:
    w, d = tts(TEXT, 'en', load_voice_style([v]), 8)
    sf.write(os.path.join(OUT, 'voice_' + os.path.basename(v)[:-5] + '.wav'), w[0], tts.sample_rate)

# blending: halfway between the first male and first female voice
def blend(a, b, t):
    sa, sb = load_voice_style([a]), load_voice_style([b])
    return Style(sa.ttl * (1 - t) + sb.ttl * t, sa.dp * (1 - t) + sb.dp * t)
m = [v for v in vs if os.path.basename(v).startswith('M')][0]; f = [v for v in vs if os.path.basename(v).startswith('F')][0]
for t in (0.25, 0.5, 0.75):
    w, d = tts(TEXT, 'en', blend(m, f, t), 8)
    sf.write(os.path.join(OUT, f'blend_{os.path.basename(m)[:-5]}_{os.path.basename(f)[:-5]}_{int(t*100)}.wav'), w[0], tts.sample_rate)

# int8 (dynamic quantisation): only MatMul/Gemm weights (depthwise convs can't run as ConvInteger)
from onnxruntime.quantization import quantize_dynamic, QuantType
def mel_dist(a, b):
    import numpy as np
    n = min(len(a), len(b)); a, b = a[:n], b[:n]
    def spec(x):
        f = np.lib.stride_tricks.sliding_window_view(x, 2048)[::512] * np.hanning(2048)
        return np.log(np.abs(np.fft.rfft(f, axis=1)) + 1e-5)
    A, B = spec(a), spec(b)
    return float(np.mean(np.abs(A - B)))
def same_noise(t, text, style, steps, seed=7):
    np.random.seed(seed); w, d = t(text, 'en', style, steps); return w[0]
ref = same_noise(tts, TEXT, style, 5)
ref2 = same_noise(tts, TEXT, style, 5, seed=8)
P(f'quality baseline: fp32 vs fp32 with different noise = {mel_dist(ref, ref2):.3f}  (lower = closer)')
for label, parts in (('int8 all MatMul', ['duration_predictor.onnx', 'text_encoder.onnx', 'vector_estimator.onnx', 'vocoder.onnx']),
                     ('int8 MatMul, fp32 vocoder', ['duration_predictor.onnx', 'text_encoder.onnx', 'vector_estimator.onnx'])):
    q = os.path.join(OUT, label.replace(' ', '_').replace(',', '')); os.makedirs(q, exist_ok=True)
    for n in ('duration_predictor.onnx', 'text_encoder.onnx', 'vector_estimator.onnx', 'vocoder.onnx', 'tts.json', 'unicode_indexer.json'):
        subprocess.run(['cp', os.path.join(onnx, n), q])
    for n in parts:
        try:
            quantize_dynamic(os.path.join(onnx, n), os.path.join(q, n), weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gemm'])
        except Exception as e:
            P('  quantise failed', n, e); subprocess.run(['cp', os.path.join(onnx, n), q])
    sz = sum(os.path.getsize(os.path.join(q, n)) for n in os.listdir(q)) / 1e6
    try:
        tq = load(q)
        bench(tq, style, 5, label)
        w = same_noise(tq, TEXT, style, 5)
        P(f'  {label}: total {sz:.0f} MB, difference from fp32 = {mel_dist(ref, w):.3f}')
        sf.write(os.path.join(OUT, f'{label.replace(" ", "_").replace(",", "")}_M1.wav'), w, tq.sample_rate)
    except Exception as e:
        P('  run failed:', label, e)
sf.write(os.path.join(OUT, 'fp32_M1_steps5.wav'), ref, tts.sample_rate)
for n in ('vector_estimator.onnx', 'vocoder.onnx', 'text_encoder.onnx', 'duration_predictor.onnx'):
    for d in glob.glob(os.path.join(OUT, 'int8*')):
        if os.path.isdir(d) and os.path.exists(os.path.join(d, n)): P(f'  {os.path.basename(d)}/{n}: {os.path.getsize(os.path.join(d, n))/1e6:.1f} MB')
P('sample rate', tts.sample_rate)
