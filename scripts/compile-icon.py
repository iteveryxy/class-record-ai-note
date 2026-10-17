"""Compile the existing geometric waveform logo into Windows package icons."""
from pathlib import Path
from PIL import Image,ImageDraw
root=Path(__file__).resolve().parents[1]/'desktop/build'
root.mkdir(parents=True,exist_ok=True)
image=Image.new('RGBA',(256,256),(0,0,0,0))
draw=ImageDraw.Draw(image)
draw.rounded_rectangle((0,0,255,255),radius=64,fill='#25866e')
for x,start,end in [(13,20,28),(18.5,15,33),(24,11,37),(29.5,17,31),(35,21,27)]:
    draw.line((x*256/48,start*256/48,x*256/48,end*256/48),fill='white',width=13)
    radius=6
    for y in (start,end):draw.ellipse((x*256/48-radius,y*256/48-radius,x*256/48+radius,y*256/48+radius),fill='white')
image.save(root/'icon.png')
image.save(root/'icon.ico',sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
