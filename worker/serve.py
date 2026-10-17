"""Desktop bundle entry point: no Python installation is needed after freezing."""
import argparse
import multiprocessing
import os
import sys
from pathlib import Path

if getattr(sys,"frozen",False):
    # PyInstaller includes imageio's FFmpeg next to its other package data.
    import imageio_ffmpeg
    os.environ.setdefault("FFMPEG_PATH",imageio_ffmpeg.get_ffmpeg_exe())

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int)
    parser.add_argument("--import-source",type=Path)
    parser.add_argument("--import-target",type=Path)
    args=parser.parse_args()
    if args.import_source and args.import_target:
        from storage import import_legacy
        import json
        print(json.dumps(import_legacy(args.import_source,args.import_target)))
        return
    if args.port is None:
        parser.error("port is required unless importing data")
    import uvicorn
    from app import app
    uvicorn.run(app,host="127.0.0.1",port=args.port,log_level="info")

if __name__=="__main__":
    multiprocessing.freeze_support()
    main()
