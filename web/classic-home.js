(function () {
    var root = document.documentElement;
    root.classList.add("js");
    var choices = document.querySelector(".voice-choice");
    var orb = document.getElementById("orb");
    var brulee = orb.querySelector(".hero-brulee");
    choices.hidden = false;
    choices.addEventListener("click", function (event) {
      var selected = event.target.closest("[data-look]");
      if (!selected) return;
      brulee.hidden = selected.dataset.look !== "brulee";
      orb.classList.toggle("shows-brulee", !brulee.hidden);
      onPause.hero(paused.hero);
      choices.querySelectorAll("button").forEach(function (button) {
        button.setAttribute("aria-pressed", String(button === selected));
      });
    });
    var calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var hasIO = "IntersectionObserver" in window;
    var coarse = window.matchMedia("(pointer: coarse)").matches;
    var small = window.innerWidth < 721;
    var wait = function (ms) { return new Promise(function (res) { setTimeout(res, ms); }); };
    // Pause buttons for everything that moves by itself (WCAG 2.2.2).
    var paused = { hero: false, marquee: false };
    var onPause = {};
    document.querySelectorAll("[data-pause]").forEach(function (btn) {
      var key = btn.getAttribute("data-pause");
      var label = btn.getAttribute("aria-label");
      btn.addEventListener("click", function () {
        paused[key] = !paused[key];
        btn.setAttribute("aria-pressed", String(paused[key]));
        btn.setAttribute("aria-label", paused[key] ? label.replace("Pause", "Play") : label);
        if (onPause[key]) onPause[key](paused[key]);
      });
    });

    // Platform: the visitor's store leads, and the header button goes straight there.
    var ua = navigator.userAgent;
    var ios = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    var android = /Android/.test(ua);
    var store = ios ? "https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726"
      : android ? "https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker" : null;
    if (ios) root.classList.add("is-ios"); else if (android) root.classList.add("is-android");
    if (store) document.querySelectorAll("[data-get-app]").forEach(function (a) { a.href = store; });

    // Header: glass once the page moves.
    var head = document.getElementById("site-head");
    function paintHead() { head.classList.toggle("is-stuck", window.scrollY > 8); }
    paintHead();
    window.addEventListener("scroll", paintHead, { passive: true });

    // Live star count for the GitHub link (same-origin JSON the worker keeps fresh).
    fetch("/star-history.json").then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
      if (!d || !d.total) return;
      document.querySelectorAll("[data-stars]").forEach(function (el) { el.textContent = new Intl.NumberFormat("en-US").format(d.total); el.hidden = false; });
    }).catch(function () {});

    var menu = document.querySelector(".menu");
    if (menu) menu.addEventListener("click", function (e) { if (e.target.closest("a")) menu.removeAttribute("open"); });

    // ---------- Numbers that count, in step with what they measure ----------
    var fmt = new Intl.NumberFormat("en-US");
    function countUp(el, delay, dur, onStep) {
      var to = parseFloat(el.getAttribute("data-to"));
      if (calm || !isFinite(to)) return;
      el.textContent = "0";
      setTimeout(function () {
        var start = performance.now();
        (function step(now) {
          var t = Math.min(1, Math.max(0, (now - start) / dur));
          var v = Math.round(to * (1 - Math.pow(1 - t, 5)));
          el.textContent = fmt.format(v);
          if (onStep) onStep(v);
          if (t < 1) requestAnimationFrame(step);
        })(start);
      }, delay);
    }

    function enterReadout(el) {
      el.querySelectorAll("[data-to]").forEach(function (n, i) { countUp(n, 150 + i * 70, 900); });
    }
    function enterDash(el) {
      var ticks = el.querySelectorAll(".tick.fill");
      var value = el.querySelector(".gauge__value");
      var total = parseFloat(value.getAttribute("data-to"));
      countUp(value, 300, 1400, function (v) {
        ticks.forEach(function (t, k) { if (v >= (k + 1) / ticks.length * total) t.classList.add("on"); });
      });
      el.querySelectorAll(".pillar__value").forEach(function (n, i) { countUp(n, 750 + i * 110, 1200); });
    }

    // ---------- Reveals ----------
    function reveal(el) {
      el.classList.add("is-in");
      el.addEventListener("transitionend", function () { el.classList.add("is-done"); }, { once: true });
      if (el.classList.contains("readout")) enterReadout(el);
      if (el.classList.contains("dash")) enterDash(el);
    }
    if (hasIO) {
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (e) { if (e.isIntersecting) { reveal(e.target); io.unobserve(e.target); } });
      }, { rootMargin: small ? "0px 0px -4% 0px" : "0px 0px -12% 0px", threshold: small ? 0.01 : 0.15 });
      document.querySelectorAll(".reveal, [data-observe]").forEach(function (el) { io.observe(el); });
    } else {
      document.querySelectorAll(".reveal, [data-observe]").forEach(function (el) { el.classList.add("is-in"); });
    }

    // ---------- Coach: the conversation plays out once, at its final size ----------
    var chat = document.querySelector("[data-chat]");
    if (chat && hasIO && !calm) {
      var fitChat = function () {
        chat.style.minHeight = "";
        chat.classList.add("measure");
        chat.style.minHeight = chat.offsetHeight + "px";
        chat.classList.remove("measure");
      };
      fitChat();
      window.addEventListener("resize", fitChat, { passive: true });
      var chatIO = new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting) return;
        chatIO.disconnect();
        var items = chat.children;
        [[0, 1, 600], [1, 1, 1150], [1, 0, 2350], [2, 1, 2350], [3, 1, 3450], [4, 1, 4050], [4, 0, 5450], [5, 1, 5450]].forEach(function (s) {
          setTimeout(function () { items[s[0]].classList.toggle("shown", !!s[1]); }, s[2]);
        });
      }, { threshold: 0.6 });
      chatIO.observe(chat);
    }

    // ---------- Bento: small loops run only while you can see them ----------
    var bento = document.querySelector(".bento");
    if (bento && hasIO) {
      new IntersectionObserver(function (entries) { bento.classList.toggle("is-live", entries[0].isIntersecting); }).observe(bento);
    }
    document.querySelectorAll(".tile").forEach(function (t) {
      t.addEventListener("pointermove", function (e) {
        var r = t.getBoundingClientRect();
        t.style.setProperty("--mx", (e.clientX - r.left) + "px");
        t.style.setProperty("--my", (e.clientY - r.top) + "px");
      });
    });

    // ---------- Photo phone: turns toward you as it passes (CSS does this where it can) ----------
    var device = document.querySelector(".snap__device");
    if (device && !calm && !(window.CSS && CSS.supports("animation-timeline: view()"))) {
      var ticking = false;
      var tilt = function () {
        ticking = false;
        var r = device.getBoundingClientRect();
        if (r.bottom < 0 || r.top > innerHeight) return;
        var p = Math.min(1, Math.max(0, 1 - (r.top + r.height / 2) / innerHeight));
        device.style.setProperty("--ry", (-8 + 12 * p).toFixed(2) + "deg");
        device.style.setProperty("--rx", (3 - 4 * p).toFixed(2) + "deg");
      };
      window.addEventListener("scroll", function () { if (!ticking) { ticking = true; requestAnimationFrame(tilt); } }, { passive: true });
      tilt();
    }

    // ---------- Screens: ease to a stop under the pointer, rest when off screen ----------
    var track = document.querySelector(".marquee__track");
    var marquee = document.querySelector(".marquee");
    if (track && marquee && track.getAnimations) {
      var drift = track.getAnimations()[0];
      if (drift) {
        var rate = 1, rateTo = 1, easing = false;
        var easeRate = function () {
          rate += (rateTo - rate) * 0.12;
          if (Math.abs(rateTo - rate) < 0.01) rate = rateTo;
          drift.updatePlaybackRate(Math.max(rate, 0.0001));
          if (rate !== rateTo) requestAnimationFrame(easeRate); else easing = false;
        };
        var setRate = function (r) { rateTo = r; if (!easing) { easing = true; requestAnimationFrame(easeRate); } };
        var resume = function () { if (!paused.marquee && !marquee.contains(document.activeElement)) setRate(1); };
        marquee.addEventListener("pointerenter", function () { setRate(0); });
        marquee.addEventListener("pointerleave", resume);
        marquee.addEventListener("focusin", function () { setRate(0); });
        marquee.addEventListener("focusout", function () { setTimeout(resume, 0); });
        onPause.marquee = function (on) { if (on) setRate(0); else resume(); };
        if (hasIO) new IntersectionObserver(function (e) { if (e[0].isIntersecting) drift.play(); else drift.pause(); }).observe(marquee);
      }
    }

    // ---------- Providers: the key picks one, and a ripple goes out ----------
    var orbit = document.querySelector(".orbit");
    if (orbit && hasIO && !calm && !small) {
      var chips = orbit.querySelectorAll(".orbit__ring span");
      var pick = 0, timer = null;
      var choose = function () {
        chips.forEach(function (c) { c.classList.remove("is-chosen"); });
        chips[pick % chips.length].classList.add("is-chosen");
        pick += 3;
        orbit.classList.remove("ping");
        void orbit.offsetWidth;
        orbit.classList.add("ping");
      };
      new IntersectionObserver(function (e) {
        if (e[0].isIntersecting) { if (!timer) { choose(); timer = setInterval(choose, 2400); } }
        else if (timer) { clearInterval(timer); timer = null; }
      }).observe(orbit);
    }

    // ---------- The orb: the app's Live Voice sphere, in WebGL ----------
    var FRAG = [
      "#ifdef GL_FRAGMENT_PRECISION_HIGH",
      "precision highp float;",
      "#else",
      "precision mediump float;",
      "#endif",
      "uniform vec2 uRes; uniform float uTime; uniform float uSpin; uniform float uFlow; uniform float uLevel; uniform float uThinking; uniform float uActive; uniform vec2 uTilt;",
      "vec3 hash3(vec3 p){p=vec3(dot(p,vec3(127.1,311.7,74.7)),dot(p,vec3(269.5,183.3,246.1)),dot(p,vec3(113.5,271.9,124.6)));return -1.0+2.0*fract(sin(p)*43758.5453123);}",
      "float noise(vec3 p){vec3 i=floor(p);vec3 f=fract(p);vec3 u=f*f*(3.0-2.0*f);",
      " return mix(mix(mix(dot(hash3(i),f),dot(hash3(i+vec3(1.,0.,0.)),f-vec3(1.,0.,0.)),u.x),",
      "                mix(dot(hash3(i+vec3(0.,1.,0.)),f-vec3(0.,1.,0.)),dot(hash3(i+vec3(1.,1.,0.)),f-vec3(1.,1.,0.)),u.x),u.y),",
      "            mix(mix(dot(hash3(i+vec3(0.,0.,1.)),f-vec3(0.,0.,1.)),dot(hash3(i+vec3(1.,0.,1.)),f-vec3(1.,0.,1.)),u.x),",
      "                mix(dot(hash3(i+vec3(0.,1.,1.)),f-vec3(0.,1.,1.)),dot(hash3(i+vec3(1.,1.,1.)),f-vec3(1.,1.,1.)),u.x),u.y),u.z);}",
      "mat3 rotY(float a){float c=cos(a),s=sin(a);return mat3(c,0.,-s,0.,1.,0.,s,0.,c);}",
      "mat3 rotX(float a){float c=cos(a),s=sin(a);return mat3(1.,0.,0.,0.,c,s,0.,-s,c);}",
      "void main(){",
      " vec2 uv=(gl_FragCoord.xy-0.5*uRes)/(0.5*min(uRes.x,uRes.y));",
      " float R=(0.6+0.02*uActive)+0.012*sin(uTime*1.3)+0.03*uLevel;",
      " float r=length(uv);",
      " vec3 pink=vec3(1.0,0.216,0.373);",
      " float halo=exp(-max(r-R,0.0)*(7.5-2.5*uLevel))*(0.34+0.06*uActive+0.3*uLevel)*smoothstep(0.98,0.7,r);",
      " vec3 col=pink*halo; float alpha=halo;",
      " if(r<R+0.02){",
      "  vec2 q=uv/R; float d=min(dot(q,q),1.0);",
      "  vec3 n=vec3(q,sqrt(1.0-d));",
      "  vec3 pn=rotY(uSpin*0.73+uTilt.x*0.7)*rotX(0.25+uTilt.y*0.5)*n;",
      "  float t=uFlow*0.8;",
      "  float f1=noise(pn*2.3+vec3(0.0,t,t*0.7));",
      "  float f2=noise(pn*4.4-vec3(t*0.8,0.0,t));",
      "  float lines=smoothstep(0.08,0.0,abs(f1))*0.95+smoothstep(0.05,0.0,abs(f2))*0.5;",
      "  lines*=(0.5+0.5*n.z)*(0.65+0.1*uActive+0.65*uLevel);",
      "  float rim=pow(1.0-n.z,2.4);",
      "  float core=exp(-d*9.0)*(1.0+0.35*uThinking*sin(uTime*7.0));",
      "  float ang=atan(uv.y,uv.x);",
      "  float ring=smoothstep(0.035,0.0,abs(sqrt(d)-0.17))*(0.55+0.45*sin(ang*18.0+uSpin*10.0));",
      "  float I=0.08+0.2*(1.0-n.z)+lines+rim*0.8+core*(0.5+0.5*uLevel)+ring*(0.45+0.45*uLevel+0.3*uThinking);",
      "  vec3 deep=vec3(0.28,0.015,0.07);",
      "  vec3 c=mix(deep,pink,clamp(I,0.0,1.0));",
      "  c=mix(c,vec3(1.0,0.84,0.9),clamp(I-1.0,0.0,1.0)*0.85);",
      "  float edge=smoothstep(R,R-0.012,r);",
      "  col=mix(col,c,edge); alpha=mix(alpha,1.0,edge);",
      " }",
      " gl_FragColor=vec4(col,alpha);",
      "}"
    ].join("\n");

    function makeOrb(box) {
      var canvas = box && box.querySelector("canvas");
      var gl = canvas && canvas.getContext("webgl", { alpha: true, premultipliedAlpha: true, antialias: false, powerPreference: "low-power" });
      if (!gl) return null;
      var compile = function (type, src) {
        var s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        return gl.getShaderParameter(s, gl.COMPILE_STATUS) ? s : null;
      };
      var vs = compile(gl.VERTEX_SHADER, "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}");
      var fs = compile(gl.FRAGMENT_SHADER, FRAG);
      if (!vs || !fs) return null;
      var prog = gl.createProgram();
      gl.attachShader(prog, vs);
      gl.attachShader(prog, fs);
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null;
      gl.useProgram(prog);
      gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      var loc = gl.getAttribLocation(prog, "p");
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      var u = {};
      ["uRes", "uTime", "uSpin", "uFlow", "uLevel", "uThinking", "uActive", "uTilt"].forEach(function (n) { u[n] = gl.getUniformLocation(prog, n); });
      box.classList.add("is-webgl");

      // Same motion model as the app's orb: loudness rises fast and falls slowly,
      // and spin and flow speed up while it thinks or talks.
      var st = { mode: "idle", level: 0, levelTarget: 0, active: 0, thinking: 0, spin: 0, flow: 0, clock: 2, tiltX: 0, tiltY: 0, tx: 0, ty: 0, visible: false, running: false, last: 0, onFrame: null };
      var draw = function () {
        var dpr = Math.min(window.devicePixelRatio || 1, coarse ? 1.5 : 1.75);
        var w = Math.round(canvas.clientWidth * dpr), h = Math.round(canvas.clientHeight * dpr);
        if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; gl.viewport(0, 0, w, h); }
        gl.uniform2f(u.uRes, w, h);
        gl.uniform1f(u.uTime, st.clock);
        gl.uniform1f(u.uSpin, st.spin);
        gl.uniform1f(u.uFlow, st.flow);
        gl.uniform1f(u.uLevel, st.level);
        gl.uniform1f(u.uThinking, st.thinking);
        gl.uniform1f(u.uActive, st.active);
        gl.uniform2f(u.uTilt, st.tiltX, st.tiltY);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      };
      var frame = function (now) {
        if (!st.visible || document.hidden || st.paused) { st.running = false; return; }
        var dt = st.last ? Math.min(0.05, (now - st.last) / 1000) : 0;
        st.last = now;
        st.clock += dt;
        var k = st.levelTarget > st.level ? 24 : 7;
        st.level += (st.levelTarget - st.level) * (1 - Math.exp(-k * dt));
        st.active += ((st.mode !== "idle" ? 1 : 0) - st.active) * (1 - Math.exp(-3 * dt));
        st.thinking += ((st.mode === "thinking" ? 1 : 0) - st.thinking) * (1 - Math.exp(-4 * dt));
        st.spin += dt * (0.22 + 0.18 * st.active + 1.5 * st.thinking + 0.6 * st.level);
        st.flow += dt * (0.25 + 0.3 * st.active + 0.5 * st.thinking + 2.4 * st.level);
        st.tiltX += (st.tx - st.tiltX) * Math.min(1, dt * 3);
        st.tiltY += (st.ty - st.tiltY) * Math.min(1, dt * 3);
        draw();
        if (st.onFrame) st.onFrame(st);
        requestAnimationFrame(frame);
      };
      st.start = function () { if (!st.running && st.visible && !document.hidden && !st.paused) { st.running = true; st.last = 0; requestAnimationFrame(frame); } };
      st.kick = function () {
        st.levelTarget = 0.5 + Math.random() * 0.4;
        setTimeout(function () { st.levelTarget = 0.1; }, 70);
      };
      if (calm) { st.active = 0.4; draw(); return st; }
      if (hasIO) new IntersectionObserver(function (e) { st.visible = e[0].isIntersecting; st.start(); }).observe(box);
      else st.visible = true;
      document.addEventListener("visibilitychange", st.start);
      st.start();
      return st;
    }

    var heroOrb = makeOrb(document.getElementById("orb"));
    var finalOrb = makeOrb(document.querySelector(".orb--final"));
    var glow = document.querySelector(".hero__glow");
    onPause.hero = function (on) {
      if (heroOrb && !calm) { heroOrb.paused = on || !brulee.hidden; heroOrb.start(); }
      var liveBox = document.querySelector(".live");
      if (liveBox) liveBox.classList.toggle("is-paused", on);
    };
    if (heroOrb && !calm) {
      heroOrb.onFrame = function (s) { if (glow) glow.style.setProperty("--level", s.level.toFixed(3)); };
      window.addEventListener("pointermove", function (e) {
        heroOrb.tx = (e.clientX / innerWidth - 0.5) * 2;
        heroOrb.ty = (e.clientY / innerHeight - 0.5) * 2;
      }, { passive: true });
    }
    if (finalOrb && !calm) {
      finalOrb.mode = "listening";
      setInterval(function () { if (finalOrb.visible && Math.random() < 0.5) finalOrb.kick(); }, 900);
      var finalStores = document.querySelector(".final .stores");
      if (finalStores) {
        finalStores.addEventListener("pointerenter", function () { finalOrb.mode = "speaking"; finalOrb.levelTarget = 0.9; });
        finalStores.addEventListener("pointerleave", function () { finalOrb.mode = "listening"; finalOrb.levelTarget = 0.1; });
      }
    }

    // ---------- Live captions: what you say, what Fud AI says back and logs ----------
    var live = document.querySelector(".live");
    var said = document.getElementById("live-said");
    var reply = document.getElementById("live-reply");
    var card = document.getElementById("live-card");
    var status = document.getElementById("live-status");
    if (live && said && !calm) {
      var NB = " ";
      var demos = [
        { said: "Log two eggs and a latte for breakfast.", reply: "Done. Logged to breakfast: 290" + NB + "calories with 20" + NB + "grams of protein.", name: "Eggs & latte", macros: "Breakfast · 20P · 11C · 18F", kcal: "290" + NB + "kcal" },
        { said: "Add a chicken burrito bowl for lunch.", reply: "Logged to lunch: 640" + NB + "calories with 46" + NB + "grams of protein.", name: "Chicken burrito bowl", macros: "Lunch · 46P · 68C · 20F", kcal: "640" + NB + "kcal" },
        { said: "I just had a protein shake.", reply: "Added to snacks: 180" + NB + "calories with 30" + NB + "grams of protein.", name: "Protein shake", macros: "Snack · 30P · 6C · 3F", kcal: "180" + NB + "kcal" }
      ];
      var name = document.getElementById("live-name"), macros = document.getElementById("live-macros"), kcal = document.getElementById("live-kcal");
      // Build word spans with DOM nodes, never HTML strings.
      var setWords = function (el, text) {
        el.replaceChildren();
        text.split(" ").forEach(function (w, i) {
          if (i) el.appendChild(document.createTextNode(" "));
          var span = document.createElement("span");
          span.className = "w";
          span.textContent = w;
          el.appendChild(span);
        });
      };
      var cloneKids = function (el) { return Array.prototype.map.call(el.childNodes, function (n) { return n.cloneNode(true); }); };
      var setStatus = function (text) { status.textContent = text; };

      // Reserve the tallest demo's height so nothing below the hero moves.
      var lastW = 0;
      var fit = function () {
        if (window.innerWidth === lastW) return;
        lastW = window.innerWidth;
        var keep = [cloneKids(said), cloneKids(reply), name.textContent, macros.textContent, kcal.textContent];
        live.style.minHeight = "";
        var max = 0;
        demos.forEach(function (d) {
          said.textContent = d.said; reply.textContent = d.reply; name.textContent = d.name; macros.textContent = d.macros; kcal.textContent = d.kcal;
          max = Math.max(max, live.offsetHeight);
        });
        said.replaceChildren.apply(said, keep[0]); reply.replaceChildren.apply(reply, keep[1]);
        name.textContent = keep[2]; macros.textContent = keep[3]; kcal.textContent = keep[4];
        live.style.minHeight = max + "px";
      };

      // Waits that only count while the demo is playing, so Pause holds mid-sentence.
      var waitLive = function (ms) {
        return new Promise(function (res) {
          var left = ms, last = performance.now();
          (function step() {
            var now = performance.now();
            if (!paused.hero) left -= now - last;
            last = now;
            if (left <= 0) res(); else setTimeout(step, Math.min(Math.max(left, 16), 100));
          })();
        });
      };
      var heroVisible = function () {
        return new Promise(function (res) {
          (function check() {
            if ((!heroOrb || heroOrb.visible) && !document.hidden && !paused.hero) res(); else setTimeout(check, 300);
          })();
        });
      };
      var orbSet = function (mode, level) { if (heroOrb) { heroOrb.mode = mode; if (level != null) heroOrb.levelTarget = level; } };
      var kick = function () { if (heroOrb) heroOrb.kick(); };

      // The first meal is already on the page; let it be read, then keep going.
      setWords(reply, reply.textContent);
      said.querySelectorAll(".w").forEach(function (w) { w.classList.add("on"); });
      reply.querySelectorAll(".w").forEach(function (w) { w.classList.add("on"); });
      card.classList.add("on");
      setStatus("Logged");
      fit();
      if (document.fonts) document.fonts.ready.then(function () { lastW = 0; fit(); });
      window.addEventListener("resize", fit, { passive: true });

      (async function loop() {
        await waitLive(3400);
        for (var k = 1; ; k++) {
          await heroVisible();
          var d = demos[k % demos.length];
          live.classList.remove("is-speaking");
          reply.querySelectorAll(".w").forEach(function (w) { w.classList.remove("on"); });
          said.querySelectorAll(".w").forEach(function (w) { w.classList.remove("on"); });
          card.classList.add("is-pending");
          card.classList.remove("is-ticking");
          await waitLive(450);
          setWords(said, d.said);
          setWords(reply, d.reply);
          setStatus("Listening…");
          orbSet("listening", 0.1);
          var sw = said.querySelectorAll(".w");
          for (var i = 0; i < sw.length; i++) { sw[i].classList.add("on"); kick(); await waitLive(110 + Math.random() * 90); }
          orbSet("thinking", 0.05);
          setStatus("Thinking…");
          await waitLive(700);
          orbSet("speaking");
          setStatus("Speaking…");
          live.classList.add("is-speaking");
          var rw = reply.querySelectorAll(".w");
          for (var j = 0; j < rw.length; j++) { rw[j].classList.add("on"); kick(); await waitLive(55 + Math.random() * 35); }
          orbSet("idle", 0);
          name.textContent = d.name;
          macros.textContent = d.macros;
          kcal.textContent = d.kcal;
          card.classList.remove("is-pending");
          card.classList.add("on", "is-logged");
          void card.offsetWidth;
          card.classList.add("is-ticking");
          setTimeout(function () { card.classList.remove("is-logged"); }, 600);
          setStatus("Logged");
          await waitLive(3400);
        }
      })();
    }
  })();
