import {createCanvas, loadImage, GlobalFonts} from '@napi-rs/canvas'
import {spawn, spawnSync} from 'node:child_process'
import {once} from 'node:events'
import {mkdir, writeFile} from 'node:fs/promises'
import {fileURLToPath} from 'node:url'
import path from 'node:path'

const HERE=path.dirname(fileURLToPath(import.meta.url)), ROOT=path.resolve(HERE,'../..')
const OUT=path.join(HERE,'exports');await mkdir(OUT,{recursive:true})
GlobalFonts.registerFromPath('/System/Library/Fonts/Supplemental/Arial Bold.ttf','Display')
GlobalFonts.registerFromPath('/System/Library/Fonts/Avenir Next.ttc','Body')
const W=1080,H=1350,FPS=30,DURATION=16,PINK='#fa557b',WHITE='#fff9f5',MUTED='#b3a4ac'
const canvas=createCanvas(W,H),c=canvas.getContext('2d')
const logo=await loadImage(path.join(ROOT,'marketing/calorie logo transparent.png'))
const home=await loadImage(path.join(ROOT,'web/assets/screenshots/home.png'))
const snap=await loadImage(path.join(ROOT,'web/assets/screenshots/snap.png'))
const clamp=x=>Math.max(0,Math.min(1,x)), ease=x=>1-(1-clamp(x))**3
const mix=(a,b,t)=>a+(b-a)*t
const progress=(t,a,b)=>ease((t-a)/(b-a))
function text(s,x,y,size,color=WHITE,align='left',weight='bold'){
 c.fillStyle=color;c.textAlign=align;c.textBaseline='alphabetic';c.font=`${weight} ${size}px ${weight==='bold'?'Display':'Body'}`;c.fillText(s,x,y)
}
function rr(x,y,w,h,r,fill,stroke){c.beginPath();c.roundRect(x,y,w,h,r);if(fill){c.fillStyle=fill;c.fill()}if(stroke){c.strokeStyle=stroke;c.lineWidth=1.5;c.stroke()}}
function alpha(a,fn){c.save();c.globalAlpha*=clamp(a);fn();c.restore()}
function label(s,x,y,color=MUTED){text(s,x,y,22,color,'left','normal')}
function logoAt(x,y,size,rotation=0){c.save();c.translate(x,y);c.rotate(rotation);c.drawImage(logo,-size/2,-size/2,size,size);c.restore()}
function reveal(s,x,y,size,t,delay=0,color=WHITE,align='left'){
 const p=progress(t,delay,delay+.65);alpha(p,()=>text(s,x,y+36*(1-p),size,color,align))
}
function background(t,light=false){
 c.fillStyle=light?'#f9e6e7':'#09090d';c.fillRect(0,0,W,H)
 const g=c.createRadialGradient(780+80*Math.sin(t*.6),550,0,600,650,830)
 g.addColorStop(0,light?'#f6c4cb':'#31101e');g.addColorStop(1,light?'#f9e6e7':'#09090d');c.fillStyle=g;c.fillRect(0,0,W,H)
 c.strokeStyle=light?'#e7bbc5':'#ffffff08';c.lineWidth=1
 for(let x=60;x<W;x+=80){c.beginPath();c.moveTo(x,0);c.lineTo(x,H);c.stroke()}
 for(let y=35;y<H;y+=80){c.beginPath();c.moveTo(0,y);c.lineTo(W,y);c.stroke()}
}
function header(t){logoAt(89,86,54);text('Fud AI',128,99,29);text('A LITTLE MILESTONE. A BIG THANK YOU.',1000,94,17,MUTED,'right','normal')}
function footer(t,chapter){
 const names=['MEET FUD AI','SNAP. TRACK.','10K+ DOWNLOADS','THANK YOU']
 label(names[chapter],76,1270)
 text('fud-ai.app',1004,1270,23,WHITE,'right','normal')
 for(let i=0;i<4;i++){
  rr(76+i*235,1300,223,3,1,'#ffffff18')
  if(t>=i*4)rr(76+i*235,1300,223*clamp((t-i*4)/4),3,1,PINK)
 }
}
function phone(img,x,y,w,angle=0,scale=1){
 const h=w*img.height/img.width;c.save();c.translate(x,y);c.rotate(angle);c.scale(scale,scale)
 c.shadowColor='#000000bb';c.shadowBlur=55;c.shadowOffsetY=28
 rr(-w/2-9,-h/2-9,w+18,h+18,52,'#0c0b0e','#75636b');c.shadowBlur=0;c.shadowOffsetY=0
 c.save();c.beginPath();c.roundRect(-w/2,-h/2,w,h,43);c.clip();c.drawImage(img,-w/2,-h/2,w,h);c.restore()
 c.restore()
}
function pill(s,x,y,w,t){alpha(t,()=>{rr(x,y,w,56,28,'#ffffff09','#ffffff24');text(s,x+w/2,y+36,22,WHITE,'center','normal')})}
let seed=21;const rand=()=>((seed=(seed*1664525+1013904223)>>>0)/4294967296)
const particles=Array.from({length:94},()=>({x:rand()*W,y:rand()*H,r:2+rand()*6,s:25+rand()*70,a:rand()*6.28,kind:rand()}))
function confetti(t,opacity=1){alpha(opacity,()=>{for(const p of particles){const y=(p.y+t*p.s)%(H+160)-80,x=p.x+22*Math.sin(t+p.a);c.save();c.translate(x,y);c.rotate(p.a+t*.8);c.fillStyle=p.kind>.5?PINK:'#ffd9e1';c.fillRect(-p.r/2,-p.r/2,p.r,p.r*1.9);c.restore()}})}
function intro(t){
 background(t);header(t)
 alpha(.75,()=>{c.strokeStyle='#fa557b22';c.lineWidth=2;for(let i=0;i<3;i++){c.beginPath();c.ellipse(540,590,220+i*48+10*Math.sin(t),220+i*48,0,0,Math.PI*2);c.stroke()}})
 const p=progress(t,0,.8);logoAt(540,535+45*(1-p),340*(.72+.28*p),-.12+.06*Math.sin(t*1.3))
 reveal('Small bites.',540,830,88,t,.3,WHITE,'center')
 reveal('One big milestone.',540,930,78,t,.6,PINK,'center')
 alpha(progress(t,.9,1.5),()=>text('Your AI calorie & nutrition tracker.',540,1010,31,MUTED,'center','normal'))
 pill('PHOTO',227,1100,190,progress(t,1.2,1.7));pill('VOICE',443,1100,190,progress(t,1.35,1.85));pill('TEXT',659,1100,190,progress(t,1.5,2));footer(t,0)
}
function product(t,global){
 background(global);header(global)
 reveal('Snap it.',76,300,102,t,.1)
 reveal('Track it.',76,412,102,t,.25,PINK)
 alpha(progress(t,.45,1),()=>{text('A little clarity',78,490,32,WHITE,'left','normal');text('with every meal.',78,537,32,MUTED,'left','normal')})
 const p=progress(t,0,.8)
 phone(home,mix(1220,760,p),785+12*Math.sin(t),350,.035*Math.sin(t*.7))
 phone(snap,mix(-250,260,p),908+12*Math.cos(t),260,-.1+.025*Math.sin(t))
 alpha(progress(t,.9,1.3),()=>{rr(80,633,318,74,23,'#fa557b');text('AI FOOD SCANNING',239,679,23,'#180910','center')})
 footer(global,1)
}
function milestone(t,global){
 background(global);header(global);confetti(t,progress(t,.25,1)*.65)
 const p=progress(t,0,.85)
 c.save();c.translate(540,643);c.scale(.8+.2*p,.8+.2*p)
 alpha(.5,()=>{c.strokeStyle='#fa557b';c.lineWidth=2;for(let i=0;i<3;i++){c.beginPath();c.arc(0,0,360+i*35+t*5,0,Math.PI*2);c.stroke()}})
 c.restore()
 reveal('WE JUST CROSSED',540,357,30,t,.05,MUTED,'center')
 const scale=.82+.18*p;c.save();c.translate(540,689);c.scale(scale,scale);text('10K+',0,0,278,PINK,'center');c.restore()
 reveal('downloads',540,789,77,t,.3,WHITE,'center')
 alpha(progress(t,.6,1.2),()=>text('App Store + Google Play + GitHub',540,902,30,WHITE,'center','normal'))
 alpha(progress(t,.9,1.5),()=>{text('Built with care. Growing because of you.',540,1035,32,MUTED,'center','normal')})
 footer(global,2)
}
function outro(t,global){
 background(global);header(global);confetti(t+4,.2)
 logoAt(540,375,220*(.82+.18*progress(t,0,.6)),.03*Math.sin(t))
 reveal('You made',540,660,103,t,.05,WHITE,'center');reveal('this happen.',540,783,103,t,.22,WHITE,'center')
 reveal('10K+ downloads. Thank you.',540,898,41,t,.4,PINK,'center')
 alpha(progress(t,.6,1.2),()=>{rr(327,996,426,91,46,PINK);text('fud-ai.app',540,1054,37,'#15090e','center')})
 alpha(progress(t,.8,1.4),()=>text('iPhone  /  Android  /  Open source',540,1152,27,MUTED,'center','normal'))
 footer(global,3)
}
function draw(t){
 c.resetTransform();c.globalAlpha=1
 if(t<3.3)intro(t);else if(t<7.3)product(t-3.3,t);else if(t<11.5)milestone(t-7.3,t);else outro(t-11.5,t)
 for(const cut of [3.3,7.3,11.5]){
  const q=(t-cut+.17)/.38
  if(q>0&&q<1){const x=-W+2*W*ease(q);c.fillStyle=PINK;c.fillRect(x,0,W,H)}
 }
}

// Original synthesized music: no third-party recording or licensed sample.
function soundtrack(){
 const sr=48000,n=sr*DURATION,buf=Buffer.alloc(44+n*4)
 buf.write('RIFF',0);buf.writeUInt32LE(36+n*4,4);buf.write('WAVEfmt ',8);buf.writeUInt32LE(16,16);buf.writeUInt16LE(1,20);buf.writeUInt16LE(2,22);buf.writeUInt32LE(sr,24);buf.writeUInt32LE(sr*4,28);buf.writeUInt16LE(4,32);buf.writeUInt16LE(16,34);buf.write('data',36);buf.writeUInt32LE(n*4,40)
 const chords=[[130.81,164.81,196,246.94],[110,130.81,164.81,196],[87.31,110,130.81,164.81],[98,123.47,146.83,196]]
 let noiseSeed=17
 for(let i=0;i<n;i++){
  const t=i/sr,phase=t%.5,bar=Math.floor(t/4)%4,chord=chords[bar]
  let left=0,right=0
  const padEnv=Math.min(1,(t%4)/.25)*Math.min(1,(4-t%4)/.4)
  for(const f of chord){const a=.025*padEnv;left+=a*Math.sin(2*Math.PI*f*t);right+=a*Math.sin(2*Math.PI*(f+.16)*t)}
  const arp=chord[Math.floor(t*4)%4]*2,arpTime=t%.25,arpEnv=Math.exp(-arpTime*17)*Math.min(1,arpTime/.006)
  const melody=.075*arpEnv*(Math.sin(2*Math.PI*arp*t)+.2*Math.sin(2*Math.PI*arp*2*t))
  const kick=t>2?.16*Math.exp(-phase*22)*Math.sin(2*Math.PI*(48*phase+3*(1-Math.exp(-phase*28)))):0
  noiseSeed=(noiseSeed*1664525+1013904223)>>>0;const noise=noiseSeed/4294967296*2-1
  const hat=t>2?.019*noise*Math.exp(-(t%.25)*90):0
  const clap=(t>2&&Math.floor(t*2)%2===1)?.033*noise*Math.exp(-phase*32):0
  let whoosh=0;for(const cut of [3.3,7.3,11.5]){const dt=t-cut;if(Math.abs(dt)<.25)whoosh+=noise*.055*Math.exp(-Math.abs(dt)*15)}
  const env=clamp(t/.2)*clamp((DURATION-t)/.6)
  left=(left+melody+kick+hat+clap+whoosh)*env;right=(right+melody+kick+hat+clap+whoosh)*env
  buf.writeInt16LE(Math.round(Math.tanh(left)*29000),44+i*4);buf.writeInt16LE(Math.round(Math.tanh(right)*29000),46+i*4)
 }
 return buf
}
await writeFile(path.join(OUT,'soundtrack.wav'),soundtrack())
for(const [name,t] of [['poster',9.6],['preview-intro',2.3],['preview-product',5.8],['preview-end',14.6]]){draw(t);await writeFile(path.join(OUT,name+'.png'),canvas.toBuffer('image/png'))}
if(process.argv.includes('--stills'))process.exit(0)
const silent=path.join(OUT,'fud-ai-10k-silent.mp4')
const ff=spawn('ffmpeg',['-y','-loglevel','error','-f','rawvideo','-pix_fmt','rgba','-s',`${W}x${H}`,'-r',String(FPS),'-i','pipe:0','-an','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',silent],{stdio:['pipe','inherit','inherit']})
const completed=once(ff,'close')
for(let frame=0;frame<DURATION*FPS;frame++){
 draw(frame/FPS)
 if(!ff.stdin.write(canvas.data()))await once(ff.stdin,'drain')
 if(frame%90===0)console.log(`Rendered ${frame}/${DURATION*FPS} frames`)
}
ff.stdin.end();const [code]=await completed;if(code!==0)throw new Error(`ffmpeg exited ${code}`)
const output=path.join(OUT,'fud-ai-10k-celebration.mp4')
const mux=spawnSync('ffmpeg',['-y','-loglevel','error','-i',silent,'-i',path.join(OUT,'soundtrack.wav'),'-map','0:v','-map','1:a','-c:v','copy','-c:a','aac','-b:a','192k','-af','loudnorm=I=-16:TP=-1.5:LRA=9','-ar','48000','-t',String(DURATION),'-movflags','+faststart',output],{stdio:'inherit'})
if(mux.status!==0)throw new Error('Audio mux failed')
console.log(output)
