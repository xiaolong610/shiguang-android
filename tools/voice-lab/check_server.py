import argparse
import io
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request,urlopen
import wave

parser=argparse.ArgumentParser();parser.add_argument('--key-file',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
key=Path(args.key_file).read_text(encoding='utf8').strip()
def call(path,body=None,auth=True):
    headers={'Authorization':'Bearer '+key}if auth else {}
    if body is not None:headers['Content-Type']='application/json'
    request=Request('http://127.0.0.1:8765'+path,data=json.dumps(body).encode()if body is not None else None,headers=headers)
    try:
        with urlopen(request,timeout=90)as response:return response.status,response.read()
    except HTTPError as error:return error.code,error.read()
code,data=call('/health');assert code==200;health=json.loads(data)
assert call('/health',auth=False)[0]==401
assert call('/v1/speech',{'text':'测试','style':'unsupported'})[0]==422
assert call('/v1/speech',{'text':'字'*281})[0]==422
start=time.perf_counter();code,data=call('/v1/speech',{'text':'海边的邮局在下午四点关门。林夏把信收好，轻轻推开了门。','style':'happy'});assert code==200
elapsed=time.perf_counter()-start
with wave.open(io.BytesIO(data))as audio:
    duration=audio.getnframes()/audio.getframerate();assert duration>1
    rate=audio.getframerate();assert rate==24000
(out/'api-sample.wav').write_bytes(data)
result={'health':health,'authenticationRequired':True,'invalidStyleRejected':True,'oversizedTextRejected':True,'audioBytes':len(data),'audioSeconds':duration,'sampleRate':rate,'requestSeconds':elapsed}
(out/'server-test.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(result,ensure_ascii=True))
