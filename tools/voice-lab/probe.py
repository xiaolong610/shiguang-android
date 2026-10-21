"""Probe locally installed CosyVoice using only a newly generated, built-in synthetic voice."""
import argparse
import gc
import json
import os
from pathlib import Path
import sys
import time

parser = argparse.ArgumentParser()
parser.add_argument('--cosyvoice', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(args.cosyvoice).resolve()
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(root))
sys.path.append(str(root / 'third_party' / 'Matcha-TTS'))
os.chdir(root)
from tools._compat import patch_torchaudio
patch_torchaudio()
from offline_support import use_local_modelscope_cache
use_local_modelscope_cache()
import numpy as np
import soundfile as sf
import torch
from cosyvoice.cli.cosyvoice import AutoModel

def save(path, chunks, rate):
    audio = torch.cat(chunks, dim=1).detach().cpu().float().numpy().reshape(-1)
    if not np.isfinite(audio).all() or len(audio) < rate:
        raise RuntimeError('Invalid generated audio')
    sf.write(path, np.clip(audio, -1, 1), rate, subtype='PCM_16')
    return dict(seconds=len(audio)/rate, sampleRate=rate, peak=float(np.max(np.abs(audio))), rms=float(np.sqrt(np.mean(audio**2))))

report = dict(device=torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU', source='Built-in synthetic Chinese female voice; no personal reference recording used.', samples=[])
seed = out / 'synthetic-reference.wav'
if not seed.exists():
    start = time.perf_counter()
    print('Loading built-in SFT voice', flush=True)
    model = AutoModel(model_dir=str(root / 'pretrained_models' / 'CosyVoice-300M-SFT'))
    chunks = [x['tts_speech'] for x in model.inference_sft('今天的风很轻，窗外的树叶慢慢落下来。请听我把这个故事讲完。', '中文女', stream=False)]
    save(seed, chunks, model.sample_rate)
    print('Reference generated in %.1fs' % (time.perf_counter()-start), flush=True)
    del model, chunks
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

start = time.perf_counter()
print('Loading local CosyVoice3', flush=True)
model = AutoModel(model_dir=str(root / 'pretrained_models' / 'Fun-CosyVoice3-0.5B'), fp16=True)
report['loadSeconds'] = time.perf_counter()-start
text = '林夏拆开信，读到最后一句时，忽然停了下来。海风吹过，她把信轻轻贴在胸口。'
for name, instruction in [('happy', '请非常开心地说一句话。'), ('sad', '请非常伤心地说一句话。')]:
    torch.manual_seed(42)
    start = time.perf_counter()
    print('Rendering '+name, flush=True)
    chunks = [x['tts_speech'] for x in model.inference_instruct2(text, 'You are a helpful assistant. '+instruction+'<|endofprompt|>', str(seed), stream=False)]
    stats = save(out / (name+'.wav'), chunks, model.sample_rate)
    stats.update(style=name, instruction=instruction, text=text, generationSeconds=time.perf_counter()-start)
    stats['realTimeFactor'] = stats['generationSeconds']/stats['seconds']
    report['samples'].append(stats)
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(stats, ensure_ascii=True), flush=True)
print('Probe complete', flush=True)
