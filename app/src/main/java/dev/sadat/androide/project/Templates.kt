package dev.sadat.androide.project

import dev.sadat.androide.AndroApp

object Templates {
    fun apply(kind: String): String {
        val ws = AndroApp.instance.workspace
        when (kind.lowercase()) {
            "react", "web" -> {
                ws.write("index.html", REACT)
                ws.write("README.md", "# React playable in Run tab")
            }
            "3d", "three" -> {
                ws.write("index.html", THREE)
            }
            "2d", "phaser" -> {
                ws.write("index.html", PHASER)
            }
            "kotlin", "android" -> {
                ws.write("src/Main.kt", KOTLIN)
                ws.write("index.html", """<html><body style="background:#0b1220;color:#e8f1ff;font-family:sans-serif"><pre>${KOTLIN.replace("<","&lt;")}</pre><p>Kotlin source in project. HTML preview here; export later.</p></body></html>""")
            }
            else -> ws.write("index.html", CANVAS)
        }
        return "template $kind written"
    }

    private const val REACT = """<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>AndroIDE React</title>
<script src="https://unpkg.com/react@18/umd/react.development.js"></script>
<script src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"></script>
<script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
<style>body{margin:0;background:#0b1220;color:#e8f1ff;font-family:system-ui}#root{padding:16px}button{background:#00e5ff;border:0;padding:10px 16px;border-radius:10px}</style>
</head><body><div id="root"></div>
<script type="text/babel">
function App(){
  const [n,setN]=React.useState(0);
  return (<div><h1>React on AndroIDE</h1><p>Score {n}</p><button onClick={()=>setN(n+1)}>Tap</button></div>);
}
ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
</script></body></html>"""

    private const val THREE = """<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>3D</title><style>body{margin:0;overflow:hidden;background:#000}#hud{position:fixed;left:8px;top:8px;color:#0ff;font-family:sans-serif}</style>
<script type="importmap">{"imports":{"three":"https://unpkg.com/three@0.160.0/build/three.module.js"}}</script>
</head><body><div id="hud">WASD / drag — 3D sandbox</div>
<script type="module">
import * as THREE from 'three';
const s=new THREE.Scene(); s.background=new THREE.Color(0x071018);
const c=new THREE.PerspectiveCamera(70,innerWidth/innerHeight,0.1,200);
c.position.set(4,3,6);
const r=new THREE.WebGLRenderer({antialias:true}); r.setSize(innerWidth,innerHeight); document.body.appendChild(r.domElement);
s.add(new THREE.HemisphereLight(0x88ccff,0x223311,1.2));
const sun=new THREE.DirectionalLight(0xffffff,1); sun.position.set(5,8,3); s.add(sun);
const floor=new THREE.Mesh(new THREE.BoxGeometry(20,0.4,20), new THREE.MeshStandardMaterial({color:0x14301c}));
floor.position.y=-0.2; s.add(floor);
const cube=new THREE.Mesh(new THREE.BoxGeometry(1,1,1), new THREE.MeshStandardMaterial({color:0x00e5ff}));
cube.position.y=0.7; s.add(cube);
addEventListener('resize',()=>{c.aspect=innerWidth/innerHeight;c.updateProjectionMatrix();r.setSize(innerWidth,innerHeight)});
function loop(t){ cube.rotation.y=t/800; r.render(s,c); requestAnimationFrame(loop);} requestAnimationFrame(loop);
</script></body></html>"""

    private const val PHASER = """<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<script src="https://cdn.jsdelivr.net/npm/phaser@3.80.1/dist/phaser.min.js"></script>
<style>body{margin:0;background:#000}</style></head><body>
<script>
const cfg={type:Phaser.AUTO,width:innerWidth,height:innerHeight,backgroundColor:'#102038',physics:{default:'arcade'},scene:{create,update}};
let p,c;
function create(){
  this.add.text(12,12,'2D Phaser — tap/arrows',{color:'#00e5ff'});
  c=this.add.rectangle(200,200,36,36,0xffd54f);
  this.physics.add.existing(c);
  this.input.on('pointermove',e=>this.physics.moveTo(c,e.x,e.y,220));
}
function update(){}
new Phaser.Game(cfg);
</script></body></html>"""

    private const val CANVAS = """<!DOCTYPE html><html><body style="margin:0;background:#0b1220">
<canvas id="c"></canvas>
<script>
const c=document.getElementById('c'),x=c.getContext('2d');
function rs(){c.width=innerWidth;c.height=innerHeight} rs(); addEventListener('resize',rs);
let px=120,py=120;
c.onpointermove=e=>{px=e.x;py=e.y};
function loop(){x.fillStyle='#0b1220';x.fillRect(0,0,c.width,c.height);
x.fillStyle='#00e5ff';x.fillRect(px-16,py-16,32,32); requestAnimationFrame(loop)} loop();
</script></body></html>"""

    private const val KOTLIN = """fun main() {
    println("AndroIDE Kotlin prototype")
    val hp = 10
    repeat(3) { println("tick $it hp=$hp") }
}
"""
}
