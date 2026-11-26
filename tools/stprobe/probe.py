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

# int8 (dynamic quantisation) of each part
from onnxruntime.quantization import quantize_dynamic, QuantType
q = os.path.join(OUT, 'int8'); os.makedirs(q, exist_ok=True)
for n in ('duration_predictor.onnx', 'text_encoder.onnx', 'vector_estimator.onnx', 'vocoder.onnx'):
    try:
        quantize_dynamic(os.path.join(onnx, n), os.path.join(q, n), weight_type=QuantType.QInt8)
        P(f'int8 {n:26s} {os.path.getsize(os.path.join(q, n))/1e6:8.1f} MB')
    except Exception as e:
        P('int8 failed for', n, e); subprocess.run(['cp', os.path.join(onnx, n), os.path.join(q, n)])
for n in ('tts.json', 'unicode_indexer.json'): subprocess.run(['cp', os.path.join(onnx, n), q])
try:
    tq = load(q)
    for st in (5, 8): wq = bench(tq, style, st, 'int8 2 threads')
    w, d = tq(TEXT, 'en', style, 8); sf.write(os.path.join(OUT, 'int8_' + os.path.basename(vs[0])[:-5] + '.wav'), w[0], tq.sample_rate)
    # vector estimator int8, vocoder fp32 (vocoders often suffer most from int8)
    mix = os.path.join(OUT, 'mix'); os.makedirs(mix, exist_ok=True)
    for n in os.listdir(q): subprocess.run(['cp', os.path.join(q, n), mix])
    subprocess.run(['cp', os.path.join(onnx, 'vocoder.onnx'), mix])
    tm = load(mix); bench(tm, style, 5, 'int8 + fp32 vocoder')
    w, d = tm(TEXT, 'en', style, 8); sf.write(os.path.join(OUT, 'int8fp32voc_' + os.path.basename(vs[0])[:-5] + '.wav'), w[0], tm.sample_rate)
except Exception as e:
    P('int8 run failed:', e)
P('sample rate', tts.sample_rate)
