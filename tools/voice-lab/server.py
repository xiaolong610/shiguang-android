"""Private CosyVoice3 HTTP adapter. Defaults to loopback; generated built-in reference only."""
import argparse
import asyncio
import contextlib
import io
import logging
import os
from pathlib import Path
import secrets
import sys

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--cosyvoice',required=True)
    parser.add_argument('--reference',required=True)
    parser.add_argument('--key-file',required=True)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port',type=int,default=8765)
    args=parser.parse_args()
    root=Path(args.cosyvoice).resolve();reference=Path(args.reference).resolve()
    if not reference.is_file():
        parser.error('Run probe.py first to generate the synthetic reference')
    key_file=Path(args.key_file).resolve();key_file.parent.mkdir(parents=True,exist_ok=True)
    if not key_file.exists():
        key_file.write_text(secrets.token_urlsafe(32),encoding='utf8')
    key=key_file.read_text(encoding='utf8').strip()
    if len(key)<24:
        parser.error('The device pairing key must contain at least 24 characters')
    sys.path.insert(0,str(root));sys.path.append(str(root/'third_party'/'Matcha-TTS'))
    os.environ.setdefault('HF_HUB_OFFLINE','1');os.environ.setdefault('TRANSFORMERS_OFFLINE','1')
    os.chdir(root)
    from tools._compat import patch_torchaudio
    patch_torchaudio()
    from offline_support import use_local_modelscope_cache
    use_local_modelscope_cache()
    import numpy as np
    import soundfile as sf
    import torch
    from cosyvoice.cli.cosyvoice import AutoModel
    from fastapi import FastAPI,Header,HTTPException,Response
    from pydantic import BaseModel,Field
    from starlette.concurrency import run_in_threadpool
    import uvicorn
    model=None
    @contextlib.asynccontextmanager
    async def lifespan(app):
        nonlocal model
        model=await run_in_threadpool(lambda:AutoModel(model_dir=str(root/'pretrained_models'/'Fun-CosyVoice3-0.5B'),fp16=True))
        class RedactBookText(logging.Filter):
            def filter(self,record):
                if record.getMessage().startswith('synthesis text '):
                    record.msg='synthesizing a narration segment';record.args=()
                return True
        logging.getLogger().addFilter(RedactBookText())
        yield
        del model
    app=FastAPI(title='Shiguang private narration',docs_url=None,redoc_url=None,openapi_url=None,lifespan=lifespan)
    gate=asyncio.Lock()
    styles={'natural':'You are a helpful assistant.<|endofprompt|>','happy':'You are a helpful assistant. 请非常开心地说一句话。<|endofprompt|>','sad':'You are a helpful assistant. 请非常伤心地说一句话。<|endofprompt|>','angry':'You are a helpful assistant. 请非常生气地说一句话。<|endofprompt|>'}
    def authorize(value):
        if not value or not secrets.compare_digest(value,'Bearer '+key):
            raise HTTPException(401,'Pairing key is missing or incorrect')
    class Request(BaseModel):
        text:str=Field(min_length=1,max_length=280)
        style:str='natural'
        voice:str='synthetic-female'
        speed:float=Field(default=1,ge=.5,le=2)
    @app.get('/health')
    async def health(authorization:str|None=Header(default=None)):
        authorize(authorization)
        return {'ready':model is not None,'model':'CosyVoice3-0.5B','styles':list(styles),'voices':['synthetic-female'],'textFrontend':model.frontend.text_frontend if model is not None else '', 'device':torch.cuda.get_device_name(0)if torch.cuda.is_available()else'CPU'}
    def render(request):
        outputs=[x['tts_speech']for x in model.inference_instruct2(request.text,styles[request.style],str(reference),stream=False,speed=request.speed)]
        if not outputs:raise RuntimeError('No synthesized audio')
        audio=torch.cat(outputs,dim=1).detach().cpu().float().numpy().reshape(-1)
        if not np.isfinite(audio).all():raise RuntimeError('Invalid synthesized audio')
        stream=io.BytesIO();sf.write(stream,np.clip(audio,-1,1),model.sample_rate,format='WAV',subtype='PCM_16')
        return stream.getvalue()
    @app.post('/v1/speech')
    async def speech(request:Request,authorization:str|None=Header(default=None)):
        authorize(authorization)
        if request.style not in styles or request.voice!='synthetic-female':raise HTTPException(422,'Unsupported style or voice')
        if not request.text.strip():raise HTTPException(422,'Text is empty')
        if gate.locked():raise HTTPException(429,'Voice model is busy; retry after the current request')
        async with gate:
            audio=await run_in_threadpool(render,request)
        return Response(audio,media_type='audio/wav',headers={'Cache-Control':'no-store'})
    uvicorn.run(app,host=args.host,port=args.port,access_log=False,limit_concurrency=8)

if __name__=='__main__':main()
