# Piper (VITS) British accent voices through sherpa-onnx: one sample per voice/speaker + speed.
import os, sys, json, time, glob, urllib.request, re
import numpy as np, soundfile as sf, sherpa_onnx
M = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, 'results.txt'), 'w')
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); log.write(s + '\n'); log.flush()
TEXT = "Martha had never trusted the sea. It took her father when she was nine, and it had been trying to take the lighthouse ever since. Is anyone there? she called into the dark."
# VCTK speaker info (accent / region)
info = {}
for u in ['https://huggingface.co/datasets/CSTR-Edinburgh/vctk/resolve/main/speaker-info.txt',
          'https://raw.githubusercontent.com/coqui-ai/TTS/dev/TTS/tts/utils/assets/vctk/speaker-info.txt',
          'https://raw.githubusercontent.com/jaywalnut310/vits/main/filelists/speaker-info.txt']:
    try:
        t = urllib.request.urlopen(u, timeout=20).read().decode('utf8', 'replace')
        for line in t.splitlines():
            m = re.match(r'\s*p?(\d{3})\s+(\d+)\s+([MF])\s+(\S+)\s*(.*)', line)
            if m: info['p' + m.group(1)] = (m.group(3), m.group(4), m.group(5).strip())
        if info: P('speaker info from', u, len(info)); break
    except Exception as e: P('no info at', u, e)
for d in sorted(glob.glob(os.path.join(M, 'vits-piper-*'))):
    name = os.path.basename(d).replace('vits-piper-', '')
    onnx = [f for f in glob.glob(os.path.join(d, '*.onnx'))][0]
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=onnx, tokens=os.path.join(d, 'tokens.txt'), data_dir=os.path.join(d, 'espeak-ng-data')), num_threads=2))
    tts = sherpa_onnx.OfflineTts(cfg)
    js = json.load(open(onnx + '.json')) if os.path.exists(onnx + '.json') else {}
    sid_map = js.get('speaker_id_map') or {}
    P(f'== {name}: {os.path.getsize(onnx)/1e6:.0f} MB, speakers={tts.num_speakers}, rate={tts.sample_rate}')
    sids = sorted(sid_map.items(), key=lambda x: x[1]) if sid_map else [(str(i), i) for i in range(max(1, tts.num_speakers))]
    for spk, sid in sids:
        t0 = time.time(); a = tts.generate(TEXT, sid=sid, speed=1.0); el = time.time() - t0
        dur = len(a.samples) / a.sample_rate
        meta = info.get(spk, ('', '', ''))
        tag = f"{meta[1]}_{meta[2]}".replace(' ', '') if meta[1] else ''
        fn = f"{name}_{spk}{'_' + tag if tag else ''}.wav".replace('/', '-')
        sf.write(os.path.join(OUT, fn), np.array(a.samples), a.sample_rate)
        P(f'  {spk:6} sid={sid:3} {meta[0]:1} {meta[1]:14} {meta[2]:22} audio={dur:5.1f}s  {dur/el:5.1f}x real time  -> {fn}')
