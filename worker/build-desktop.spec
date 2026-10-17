from PyInstaller.utils.hooks import collect_all, collect_submodules, copy_metadata

datas=[]
binaries=[]
hiddenimports=[]
for package in ['funasr','modelscope','modelscope_hub','imageio_ffmpeg','sentencepiece','soundfile','kaldiio']:
    d,b,h=collect_all(package)
    datas+=d;binaries+=b;hiddenimports+=h
for package in ['funasr','modelscope','omegaconf','hydra-core','librosa','numpy','torch','torchaudio','tqdm','requests','PyYAML','transformers','huggingface-hub','scipy']:
    datas+=copy_metadata(package)
hiddenimports+=collect_submodules('uvicorn')
hiddenimports+=['uvicorn.logging','uvicorn.protocols.http.h11_impl','uvicorn.protocols.websockets.websockets_impl','uvicorn.lifespan.on','funasr.auto.auto_model']
a=Analysis(['serve.py'],pathex=['.'],binaries=binaries,datas=datas,hiddenimports=hiddenimports,
           hookspath=[],hooksconfig={},runtime_hooks=[],excludes=['tkinter','IPython','matplotlib','pytest'],noarchive=False)
pyz=PYZ(a.pure)
exe=EXE(pyz,a.scripts,[],exclude_binaries=True,name='ClassNotesWorker',debug=False,
        bootloader_ignore_signals=False,strip=False,upx=False,console=True,disable_windowed_traceback=False)
coll=COLLECT(exe,a.binaries,a.datas,strip=False,upx=False,name='ClassNotesWorker')
