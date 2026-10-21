import argparse
import json
from pathlib import Path
import re
import soundfile as sf
from scipy.signal import resample_poly
import whisper

parser=argparse.ArgumentParser();parser.add_argument('--model',required=True);parser.add_argument('--audio-dir',required=True);args=parser.parse_args()
root=Path(args.audio_dir);model=whisper.load_model(args.model,device='cuda')
report=[]
for filename in ['happy.wav','sad.wav','api-sample.wav']:
    audio,rate=sf.read(root/filename,dtype='float32')
    if audio.ndim>1:audio=audio.mean(axis=1)
    if rate!=16000:audio=resample_poly(audio,16000,rate).astype('float32')
    result=model.transcribe(audio,language='zh',fp16=True,verbose=False,condition_on_previous_text=False)
    report.append({'file':filename,'transcript':result['text'],'segments':len(result['segments']),'model':'Whisper small (local)','scope':'Automatic content check only; does not prove perceived emotion or listening quality.'})
(root/'asr-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(report,ensure_ascii=True))
