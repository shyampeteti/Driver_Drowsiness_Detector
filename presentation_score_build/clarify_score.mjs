import fs from 'node:fs/promises';
import path from 'node:path';
import {FileBlob,PresentationFile} from '@oai/artifact-tool';

const root='C:/Users/shyam/AndroidStudioProjects/Driver_Drowsiness_Detector';
const input=path.join(root,'output/DriveSafe_Driver_Safety_Project_Presentation_Score_Explained.pptx');
const build=path.join(root,'presentation_score_build');
const off='#FAFAF7', ink='#1E2D38', body='#4B5860', muted='#707B80', teal='#557B77', line='#D7DDDC', amber='#A7783A', red='#9C4B45';
const p=await PresentationFile.importPptx(await FileBlob.load(input));
const before=await p.inspect({kind:'slide,textbox,shape,image,notes',maxChars:300000});
const originalSlides=p.slides.items.length;
if(originalSlides!==20) throw new Error(`Expected 21 slides, found ${originalSlides}`);
const removedIds=before.records.filter(r=>r.slideIndex===6 && ['shape','textbox'].includes(r.kind)).map(r=>r.id);
p.delete(removedIds);
const {slide:alertSlide}=p.slides.insert({after:p.slides.items[6]});
const escalationFooters=before.records.filter(r=>r.kind==='textbox' && r.slideIndex>6 && /^\d{2}$/.test((r.text||'').trim()) && r.position?.left>=1150);
for(const r of escalationFooters) p.resolve(r.id).text=String(Number(r.text)+1).padStart(2,'0');

function addText(slide,text,x,y,w,h,size,color=body,bold=false,align='left'){
 const s=slide.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{style:'solid',fill:'none',width:0}});
 s.text=text; s.text.style={typeface:'Arial',fontSize:size,color,bold,alignment:align,verticalAlignment:'top',wrap:'square',autoFit:'shrinkText',insets:0,lineSpacing:1.08}; return s;
}
function addRect(slide,x,y,w,h,fill,lineFill='none',lineWidth=0){
 return slide.shapes.add({geometry:'rect',position:{left:x,top:y,width:w,height:h},fill,line:{style:'solid',fill:lineFill,width:lineWidth}});
}
function addHeader(slide,title,section,slideNo){
 slide.background.fill=off;
 addText(slide,section,54,28,550,20,13,teal,true);
 addText(slide,title,54,58,1170,53,34,ink,true);
 addRect(slide,54,122,1172,1,line);
 addText(slide,'DriveSafe  |  Project Presentation',54,690,660,16,11,muted,false);
 addText(slide,String(slideNo).padStart(2,'0'),1170,688,55,18,12,muted,true,'right');
}

// Rebuild slide 7 as a plain, editable weighted-score explanation.
const scoreSlide=p.slides.items[6];
addHeader(scoreSlide,'How the drowsiness score is calculated','04  METHODOLOGY',7);
addText(scoreSlide,'Exact formula',62,145,1120,24,18,ink,true);
addText(scoreSlide,'Final score = clamp(0, 100, 0.40 × closure score + 0.25 × PERCLOS score + 0.20 × prolonged-closure frequency score + 0.15 × average episode score + current yawn bonus + current nod bonus)',62,174,1150,48,15,body,false);
addText(scoreSlide,'Each component is scored from 0 to 100 before weighting.',62,226,1120,24,15,muted,false);
const rows=[
 ['40%','Continuous closure duration','Longer current closure raises this component. A closure of 2 seconds reaches its maximum.'],
 ['25%','PERCLOS','Closed-eye time ÷ valid observed eye time in the last 30 seconds. It needs 5 seconds of valid data; unknown gaps are skipped.'],
 ['20%','Prolonged-closure frequency','Counts closures of at least 500 ms in a rolling 45-second window: 0→0, 1→30, 2→65, 3 or more→100.'],
 ['15%','Average closure duration','Uses recent completed episodes: 150 ms or less maps to 0; 600 ms or more maps to 100, with linear scaling between.'],
];
rows.forEach((r,i)=>{
 const y=303+i*70;
 addText(scoreSlide,r[0],64,y+2,75,30,21,teal,true);
 addText(scoreSlide,r[1],158,y,560,25,19,ink,true);
 addText(scoreSlide,r[2],158,y+31,590,49,16,body,false);
 if(i<rows.length-1) addRect(scoreSlide,62,y+62,680,1,line);
});
addRect(scoreSlide,784,198,1,354,line);
addText(scoreSlide,'Temporary event bonuses',824,303,390,29,20,ink,true);
addText(scoreSlide,'Yawn',824,350,110,24,17,teal,true);
addText(scoreSlide,'+12 for each new event, up to 25.',934,350,282,44,16,body,false);
addText(scoreSlide,'Nod',824,392,110,24,17,teal,true);
addText(scoreSlide,'+15 for each new event, up to 30.',934,392,282,44,16,body,false);
addText(scoreSlide,'Both bonuses fade by 15% per processed frame.',824,430,390,40,16,body,false);
addRect(scoreSlide,824,480,390,1,line);
addText(scoreSlide,'Final score',824,491,150,24,17,ink,true);
addText(scoreSlide,'Weighted total plus current event bonuses, capped at 100.',824,520,390,51,16,body,false);
scoreSlide.speakerNotes.text='The four weighted components and event bonuses are implemented in DrowsinessScoringEngine.kt. PERCLOS timing and missing-data behavior are in PerclosCalculator.kt. Prolonged-episode history is in ClosureEpisodeTracker.kt. Each component contributes a weighted amount; yawns and nods add temporary bonuses after the weighted sum. The final score is clamped to 0–100.';

// Add slide 8 to explain state thresholds, duration overrides, recovery, and face loss.
addHeader(alertSlide,'Alert thresholds and recovery','04  METHODOLOGY',8);
addText(alertSlide,'Either the score threshold or the closure-duration threshold can trigger the higher alert state.',62,151,1150,31,20,ink,true);
const states=[
 ['MONITORING','Score < 50 and closure < 1000 ms','No drowsiness alert is active.',teal],
 ['DROWSY','Score ≥ 50 or closure ≥ 1000 ms','Raises the amber warning.',amber],
 ['CRITICAL','Score ≥ 75 or closure ≥ 2000 ms','Raises the red critical alert.',red],
];
states.forEach((r,i)=>{
 const y=213+i*82;
 addText(alertSlide,r[0],64,y,205,28,20,r[3],true);
 addText(alertSlide,r[1],294,y,460,28,18,ink,true);
 addText(alertSlide,r[2],790,y,405,34,17,body,false);
 if(i<states.length-1) addRect(alertSlide,62,y+57,1155,1,line);
});
addRect(alertSlide,62,475,1155,1,line);
addText(alertSlide,'Escalation and recovery',62,496,400,27,19,ink,true);
addText(alertSlide,'Escalation happens immediately. Downgrading an alert requires 1.5 seconds of confirmed open-eye observations.',62,533,1140,42,17,body,false);
addText(alertSlide,'Face loss is separate',62,588,225,24,17,red,true);
addText(alertSlide,'After face lock, 1.5 seconds without a visible face triggers its own critical warning. Missing face data does not add to the drowsiness score.',298,588,900,50,16,body,false);
alertSlide.speakerNotes.text='Thresholds and recovery hysteresis are implemented in DrowsinessScoringEngine.kt. Face lock and independent face-loss timing are in FaceVisibilityTracker.kt. Critical alert playback and warning cooldown are coordinated by DrowsinessDetector.kt and AlertManager.kt.';

const after=await p.inspect({kind:'slide,textbox,shape,image,notes',maxChars:400000});
const textMap=o=>new Map(o.records.filter(r=>r.kind==='textbox').map(r=>[r.id,r.text]));
const beforeText=textMap(before), afterText=textMap(after);
for(const r of before.records.filter(r=>r.kind==='textbox' && r.slideIndex!==6)){
 if(r.slideIndex>6 && /^\d{2}$/.test((r.text||'').trim()) && r.position?.left>=1150){
  const expected=String(Number(r.text)+1).padStart(2,'0');
  if(afterText.get(r.id)!==expected) throw new Error(`Slide number update failed for ${r.id}`);
 } else if(afterText.get(r.id)!==r.text) throw new Error(`Existing slide text changed outside score content: ${r.id}`);
}
const imagesBefore=before.records.filter(r=>r.kind==='image').length;
const imagesAfter=after.records.filter(r=>r.kind==='image').length;
if(imagesAfter!==imagesBefore) throw new Error(`Image inventory changed from ${imagesBefore} to ${imagesAfter}`);
if(p.slides.items.length!==21) throw new Error(`Expected 21 slides, found ${p.slides.items.length}`);
await fs.mkdir(build,{recursive:true});
for(const n of [7,8]){
 const png=await p.export({slide:p.slides.items[n-1],format:'png',scale:1});
 await fs.writeFile(path.join(build,`slide-${n}.png`),new Uint8Array(await png.arrayBuffer()));
}
const draft=path.join(build,'DriveSafe_Score_Explained.draft.pptx');
await (await PresentationFile.exportPptx(p)).save(draft);
await fs.writeFile(path.join(build,'inventory.json'),JSON.stringify({slides:p.slides.items.length,imagesBefore:imagesBefore,imagesAfter:imagesAfter,notesAfter:after.records.filter(r=>r.kind==='notes').length},null,2));
console.log(JSON.stringify({slides:p.slides.items.length,draft}));
