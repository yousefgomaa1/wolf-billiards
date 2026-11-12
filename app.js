(() => {
  'use strict';

  // ==========================================
  // WOLF BILLIARDS - PHYSICS ENGINE
  // ==========================================

  const canvas = document.getElementById('table');
  const ctx = canvas.getContext('2d');

  const powerInput = document.getElementById('power');
  const powerValue = document.getElementById('powerValue');

  const spinPad = document.getElementById('spinPad');
  const spinDot = document.getElementById('spinDot');
  const spinValue = document.getElementById('spinValue');
  
  const statShots = document.getElementById('statShots');
  const statPocketed = document.getElementById('statPocketed');
  const hintText = document.getElementById('hintText');
  const editBtn = document.getElementById('editBtn');
  const imageBtn = document.getElementById('imageBtn');
  const imageInput = document.getElementById('imageInput');

  const W = 900;
  const H = 500;
  const R = 11;
  const DIAMETER = R * 2;

  canvas.width = W;
  canvas.height = H;

  const bounds = {
    left: 48,
    right: 852,
    top: 48,
    bottom: 452
  };

  // Physics settings
  const RESTITUTION = 0.96;
  const WALL_RESTITUTION = 0.92;
  const FRICTION = 0.985;

  const DT = 1 / 60;
  const SUBSTEPS = 2;
  const MAX_STEPS = 1200;

  // Pocket settings
  const POCKET_RADIUS = 23;

  const pockets = [
    { x: 48, y: 48 },
    { x: 450, y: 48 },
    { x: 852, y: 48 },
    { x: 48, y: 452 },
    { x: 450, y: 452 },
    { x: 852, y: 452 }
  ];

  // جدول ألوان الكور كامل (1-15)
  const BALL_TYPES = [
    { num: 1, color: '#e9bd42', stripe: false }, // أصفر
    { num: 2, color: '#2877cf', stripe: false }, // أزرق
    { num: 3, color: '#d84c43', stripe: false }, // أحمر
    { num: 4, color: '#7b4aa8', stripe: false }, // بنفسجي
    { num: 5, color: '#e98b32', stripe: false }, // برتقالي
    { num: 6, color: '#269b71', stripe: false }, // أخضر
    { num: 7, color: '#9e3442', stripe: false }, // نبيتي
    { num: 8, color: '#1a1a1a', stripe: false }, // أسود (8)
    { num: 9, color: '#e9bd42', stripe: true },
    { num: 10, color: '#2877cf', stripe: true },
    { num: 11, color: '#d84c43', stripe: true },
    { num: 12, color: '#7b4aa8', stripe: true },
    { num: 13, color: '#e98b32', stripe: true },
    { num: 14, color: '#269b71', stripe: true },
    { num: 15, color: '#9e3442', stripe: true }
  ];

  // ==========================================
  // STATE
  // ==========================================

  let balls = [];
  let angle = -0.12;
  let dragging = false;
  let running = false;
  let paused = false;
  let ballInHand = false;
  let editingBalls = false;
  let selectedBallIndex = -1;

  let trails = [];
  let sparks = [];
  let prediction = [];
  let animationId = null;

  let shotsTaken = 0;
  let ballsPocketed = 0;

  const spinState = { x: 0, y: 0 };
  let audioCtx = null;
  let lastCollisionSound = 0;
  let liveMode = false;

  const cue = {
    x: 260, y: 250, vx: 0, vy: 0,
    color: '#f4f1e8', number: 0, pocketed: false, rotation: 0, stripe: false
  };

  // ==========================================
  // AUDIO ENGINE
  // ==========================================

  function initAudio() {
    if (!audioCtx) {
      try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) {}
    }
    if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
  }

  function playCollisionSound(intensity) {
    if (!audioCtx || !liveMode) return;
    const now = performance.now();
    if (now - lastCollisionSound < 28) return;
    lastCollisionSound = now;

    const t = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = 'triangle';
    osc.frequency.setValueAtTime(900 + Math.random() * 350, t);
    osc.frequency.exponentialRampToValueAtTime(380, t + 0.06);

    const vol = Math.min(0.18, Math.max(0.02, intensity * 0.12));
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(vol, t + 0.005);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.09);

    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start(t); osc.stop(t + 0.1);
  }

  function playWallSound(intensity) {
    if (!audioCtx || !liveMode) return;
    const t = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(180, t);
    osc.frequency.exponentialRampToValueAtTime(70, t + 0.12);

    const vol = Math.min(0.14, Math.max(0.02, intensity * 0.08));
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(vol, t + 0.005);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.14);

    osc.connect(gain); gain.connect(audioCtx.destination);
    osc.start(t); osc.stop(t + 0.16);
  }

  function playPocketSound() {
    if (!audioCtx || !liveMode) return;
    const t = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(420, t);
    osc.frequency.exponentialRampToValueAtTime(90, t + 0.35);

    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.16, t + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.4);

    osc.connect(gain); gain.connect(audioCtx.destination);
    osc.start(t); osc.stop(t + 0.45);
  }

  // ==========================================
  // POWER & UI
  // ==========================================

  function getPower() { return Number(powerInput.value); }
  function getShotSpeed() { return 260 + getPower() * 8.5; }

  function updateSpinUI() {
    const maxOffset = 34;
    spinDot.style.left = `calc(50% + ${spinState.x * maxOffset}px)`;
    spinDot.style.top = `calc(50% - ${spinState.y * maxOffset}px)`;
    spinValue.textContent = `${Math.round(spinState.x * 100)} / ${Math.round(spinState.y * 100)}`;
  }

  function updateStats() {
    statShots.textContent = shotsTaken;
    statPocketed.textContent = ballsPocketed;
  }

  // ==========================================
  // RESET (تهيئة الـ 15 كورة + البيضاء)
  // ==========================================

  function reset() {
    editingBalls = false;
    selectedBallIndex = -1;
    editBtn.textContent = 'تعديل الكرات';
    balls = [{ ...cue, rotation: 0 }];
    
    // توليد أماكن المثلث (5 صفوف)
    const startX = 580;
    const startY = 250;
    const spacingX = DIAMETER * Math.cos(Math.PI / 6); // ~19.05
    const spacingY = DIAMETER; // 22
    const positions = [];

    for (let row = 0; row < 5; row++) {
      for (let col = 0; col <= row; col++) {
        const x = startX + row * spacingX;
        const y = startY + (col - row / 2) * spacingY;
        positions.push([x, y]);
      }
    }

    // خلط الكور عشوائياً
    const shuffledBalls = [...BALL_TYPES].sort(() => Math.random() - 0.5);
    
    // تأكيد إن كورة 8 في نص الصف التالت (Index 4)
    const eightIndex = shuffledBalls.findIndex(b => b.num === 8);
    if (eightIndex !== -1 && eightIndex !== 4) {
      const temp = shuffledBalls[4];
      shuffledBalls[4] = shuffledBalls[eightIndex];
      shuffledBalls[eightIndex] = temp;
    }

    positions.forEach((p, i) => {
      const bData = shuffledBalls[i];
      balls.push({
        x: p[0], y: p[1], vx: 0, vy: 0,
        color: bData.color,
        number: bData.num,
        stripe: bData.stripe,
        pocketed: false, rotation: 0
      });
    });

    trails = []; sparks = []; prediction = [];
    running = false; paused = false; ballInHand = false;
    shotsTaken = 0; ballsPocketed = 0;
    
    spinState.x = 0; spinState.y = 0;
    updateSpinUI();
    updateStats();

    if (animationId !== null) { cancelAnimationFrame(animationId); animationId = null; }
    document.getElementById('pauseBtn').textContent = 'إيقاف مؤقت';
    hintText.textContent = 'اسحب مؤشر الاتجاه حول الكرة البيضاء لتحديد زاوية الضربة.';

    draw();
  }

  // ==========================================
  // POCKET DETECTION
  // ==========================================

  function checkPocket(ball) {
    for (const pocket of pockets) {
      const distance = Math.hypot(ball.x - pocket.x, ball.y - pocket.y);
      const captureRadius = (pocket.x === 450) ? 15 : 22;

      if (distance < captureRadius) {
        ball.pocketed = true;
        ball.x = pocket.x; ball.y = pocket.y;
        ball.vx = 0; ball.vy = 0;
        
        if (ball.number !== 0) ballsPocketed++;
        playPocketSound();
        return true;
      }
    }
    return false;
  }

  function isNearPocket(x, y) {
    return pockets.some(p => {
      const captureRadius = (p.x === 450) ? 15 : 22;
      return Math.hypot(x - p.x, y - p.y) < captureRadius + 5;
    });
  }

  // ==========================================
  // WALL & BALL COLLISIONS
  // ==========================================

  function handleWalls(ball) {
    if (ball.pocketed) return;
    let hit = false; let intensity = 0;

    if (ball.x - R < bounds.left) {
      if (!isNearPocket(ball.x, ball.y)) {
        intensity = Math.max(intensity, Math.abs(ball.vx));
        ball.x = bounds.left + R; ball.vx = Math.abs(ball.vx) * WALL_RESTITUTION; hit = true;
      }
    }
    if (ball.x + R > bounds.right) {
      if (!isNearPocket(ball.x, ball.y)) {
        intensity = Math.max(intensity, Math.abs(ball.vx));
        ball.x = bounds.right - R; ball.vx = -Math.abs(ball.vx) * WALL_RESTITUTION; hit = true;
      }
    }
    if (ball.y - R < bounds.top) {
      if (!isNearPocket(ball.x, ball.y)) {
        intensity = Math.max(intensity, Math.abs(ball.vy));
        ball.y = bounds.top + R; ball.vy = Math.abs(ball.vy) * WALL_RESTITUTION; hit = true;
      }
    }
    if (ball.y + R > bounds.bottom) {
      if (!isNearPocket(ball.x, ball.y)) {
        intensity = Math.max(intensity, Math.abs(ball.vy));
        ball.y = bounds.bottom - R; ball.vy = -Math.abs(ball.vy) * WALL_RESTITUTION; hit = true;
      }
    }

    if (hit && intensity > 60) playWallSound(intensity);
  }

  function resolveBallPairCollision(a, b) {
    if (a.pocketed || b.pocketed) return false;

    let dx = b.x - a.x, dy = b.y - a.y;
    let distance = Math.hypot(dx, dy);
    if (distance >= DIAMETER) return false;

    let nx = 0, ny = 0;
    if (distance < 0.0001) {
      const relVx = b.vx - a.vx, relVy = b.vy - a.vy;
      const fallbackX = relVx || 1, fallbackY = relVy || 0;
      const fallbackLength = Math.hypot(fallbackX, fallbackY) || 1;
      nx = fallbackX / fallbackLength; ny = fallbackY / fallbackLength;
      distance = 0.0001; dx = nx * distance; dy = ny * distance;
    } else {
      nx = dx / distance; ny = dy / distance;
    }

    const overlap = DIAMETER - distance;
    if (overlap > 0) {
      const separation = overlap * 0.5;
      a.x -= nx * separation; a.y -= ny * separation;
      b.x += nx * separation; b.y += ny * separation;

      const clamp = (ball) => {
        if (ball.pocketed || isNearPocket(ball.x, ball.y)) return;
        if (ball.x - R < bounds.left) ball.x = bounds.left + R;
        if (ball.x + R > bounds.right) ball.x = bounds.right - R;
        if (ball.y - R < bounds.top) ball.y = bounds.top + R;
        if (ball.y + R > bounds.bottom) ball.y = bounds.bottom - R;
      };
      clamp(a); clamp(b);
    }

    const relativeVelocityX = b.vx - a.vx;
    const relativeVelocityY = b.vy - a.vy;
    const velocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;
    if (velocityAlongNormal >= 0) return false;

    const impulse = (-velocityAlongNormal) * (1 + RESTITUTION) / 2;
    a.vx -= impulse * nx; a.vy -= impulse * ny;
    b.vx += impulse * nx; b.vy += impulse * ny;

    // Spark Effect
    if (Math.abs(velocityAlongNormal) > 150 && liveMode) {
      const cx = a.x + nx * R;
      const cy = a.y + ny * R;
      for (let i = 0; i < 3; i++) {
        sparks.push({
          x: cx, y: cy,
          vx: (Math.random() - 0.5) * 80,
          vy: (Math.random() - 0.5) * 80,
          life: 1, color: Math.random() > 0.5 ? '#ffdc78' : '#ffffff'
        });
      }
    }

    if (Math.abs(velocityAlongNormal) > 60) playCollisionSound(Math.abs(velocityAlongNormal));

    const cueBall = a.number === 0 ? a : (b.number === 0 ? b : null);
    if (cueBall && cueBall.spin && (Math.abs(cueBall.spin.x) > 0.01 || Math.abs(cueBall.spin.y) > 0.01)) {
      const toObjX = cueBall === a ? nx : -nx;
      const toObjY = cueBall === a ? ny : -ny;

      const followStrength = cueBall.spin.y * 120;
      cueBall.vx += toObjX * followStrength;
      cueBall.vy += toObjY * followStrength;

      const tanX = -toObjY, tanY = toObjX;
      const objBall = cueBall === a ? b : a;
      const throwStrength = cueBall.spin.x * 35;
      objBall.vx += tanX * throwStrength;
      objBall.vy += tanY * throwStrength;

      cueBall.spin.x *= 0.4; cueBall.spin.y *= 0.4;
    }

    return true;
  }

  function resolveBallCollisions(list) {
    const maxPasses = 6;
    for (let pass = 0; pass < maxPasses; pass++) {
      let collisionDetected = false;
      for (let i = 0; i < list.length; i++) {
        const a = list[i];
        if (a.pocketed) continue;
        for (let j = i + 1; j < list.length; j++) {
          const b = list[j];
          if (b.pocketed) continue;
          if (resolveBallPairCollision(a, b)) collisionDetected = true;
        }
      }
      if (!collisionDetected) break;
    }
  }

  // ==========================================
  // PHYSICS ENGINE & PARTICLES
  // ==========================================

  function physicsStep(list, friction = true) {
    const subDT = DT / SUBSTEPS;
    for (let sub = 0; sub < SUBSTEPS; sub++) {
      for (const b of list) {
        if (b.pocketed) continue;
        b.x += b.vx * subDT; b.y += b.vy * subDT;
        if (checkPocket(b)) continue;
        handleWalls(b);

        if (friction) {
          const frictionFactor = Math.pow(FRICTION, subDT / DT);
          b.vx *= frictionFactor; b.vy *= frictionFactor;
          if (b.spin) {
            const spinDecay = Math.pow(0.995, subDT / DT);
            b.spin.x *= spinDecay; b.spin.y *= spinDecay;
          }
        }

        const speed = Math.hypot(b.vx, b.vy);
        if (speed > 0.5) b.rotation = (b.rotation || 0) + speed * subDT * 0.15;
        if (speed < 0.1) { b.vx = 0; b.vy = 0; }
      }
      resolveBallCollisions(list);
    }
  }

  function updateSparks() {
    for (let i = sparks.length - 1; i >= 0; i--) {
      const s = sparks[i];
      s.x += s.vx * DT; s.y += s.vy * DT;
      s.life -= DT * 3;
      if (s.life <= 0) sparks.splice(i, 1);
    }
  }

  // ==========================================
  // AIM RAYCAST & TARGET
  // ==========================================

  function findTargetBall() {
    const white = balls[0];
    if (!white || white.pocketed) return null;

    const dx = Math.cos(angle), dy = Math.sin(angle);
    let nearestBall = null, nearestT = Infinity;

    for (let i = 1; i < balls.length; i++) {
      const b = balls[i];
      if (b.pocketed) continue;

      const ox = white.x - b.x, oy = white.y - b.y;
      const dot = ox * dx + oy * dy;
      const discriminant = dot * dot - (ox * ox + oy * oy - DIAMETER * DIAMETER);
      if (discriminant < 0) continue;

      const t = -dot - Math.sqrt(discriminant);
      if (t > 0.01 && t < nearestT) { nearestT = t; nearestBall = b; }
    }
    if (!nearestBall) return null;
    return { ball: nearestBall, t: nearestT };
  }

  // ==========================================
  // PREDICTION ENGINE
  // ==========================================

  function simulatePrediction() {
    const simulated = balls.map(b => ({ ...b, spin: b.spin ? { ...b.spin } : null }));
    const white = simulated[0];
    if (!white || white.pocketed || ballInHand) return [];

    const speed = getShotSpeed();
    white.vx = Math.cos(angle) * speed; white.vy = Math.sin(angle) * speed;
    white.spin = { x: spinState.x, y: spinState.y };

    const paths = simulated.map(b => ({
      number: b.number, color: b.color,
      points: b.pocketed ? [] : [{ x: b.x, y: b.y }], end: null, pocketed: false
    }));

    for (let step = 0; step < MAX_STEPS; step++) {
      physicsStep(simulated);
      let movingCount = 0;
      simulated.forEach((b, i) => {
        if (!b.pocketed) {
          const sp = Math.hypot(b.vx, b.vy);
          if (sp >= 0.1) {
            movingCount++;
            if (step % 2 === 0) paths[i].points.push({ x: b.x, y: b.y });
          }
        }
      });
      if (movingCount === 0) break;
    }

    paths.forEach((path, i) => {
      const b = simulated[i];
      path.end = { x: b.x, y: b.y };
      path.pocketed = b.pocketed;
      if (!b.pocketed) path.points.push({ x: b.x, y: b.y });
    });

    return paths;
  }

  // ==========================================
  // DRAW FUNCTIONS
  // ==========================================

  function drawPredictions() {
    if (running || ballInHand) return;
    prediction = simulatePrediction();

    prediction.forEach((path, i) => {
      if (path.points.length < 2) return;
      ctx.save();
      ctx.beginPath();
      path.points.forEach((p, index) => {
        if (index === 0) ctx.moveTo(p.x, p.y); else ctx.lineTo(p.x, p.y);
      });

      // تعديل: الخط الأبيض للكورة البيضاء، والباقي سميك ولون الكورة
      ctx.strokeStyle = i === 0 ? '#ffffff' : path.color;
      ctx.lineWidth = i === 0 ? 3 : 4.5; // تخانة الخط
      ctx.stroke();

      if (path.points.length > 2 && !path.pocketed) {
        ctx.beginPath(); ctx.arc(path.end.x, path.end.y, 5, 0, Math.PI * 2);
        ctx.fillStyle = path.color; ctx.fill();
        ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1; ctx.stroke();
      }

      if (path.pocketed) {
        ctx.beginPath(); ctx.arc(path.end.x, path.end.y, 5, 0, Math.PI * 2);
        ctx.fillStyle = path.color; 
        ctx.fill();
        ctx.strokeStyle = 'rgba(255,255,255,0.7)'; 
        ctx.lineWidth = 1.5;
        ctx.stroke(); 
      }
      ctx.restore();
    });
  }

  function drawAimAssist() {
    if (running || ballInHand) return;
    const target = findTargetBall();
    if (!target) return;

    const white = balls[0];
    const b = target.ball;
    const dx = Math.cos(angle), dy = Math.sin(angle);
    const gx = white.x + dx * target.t, gy = white.y + dy * target.t;

    let odx = b.x - gx, ody = b.y - gy;
    const olen = Math.hypot(odx, ody) || 1;
    odx /= olen; ody /= olen;

    ctx.save();
    ctx.beginPath(); ctx.arc(gx, gy, R, 0, Math.PI * 2);
    ctx.strokeStyle = 'rgba(255,255,255,0.75)'; ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 3]); ctx.stroke();

    const cpx = gx + odx * R, cpy = gy + ody * R;
    ctx.beginPath(); ctx.arc(cpx, cpy, 3, 0, Math.PI * 2);
    ctx.fillStyle = '#ffdc78'; ctx.fill();
    ctx.restore();
  }

  function drawTable() {
    ctx.fillStyle = '#392719'; roundRect(10, 10, 880, 480, 24); ctx.fill();
    ctx.fillStyle = '#126044'; roundRect(31, 31, 838, 438, 18); ctx.fill();
    ctx.fillStyle = '#0d5039'; roundRect(48, 48, 804, 404, 10); ctx.fill();

    pockets.forEach(p => {
      ctx.beginPath(); ctx.arc(p.x, p.y, POCKET_RADIUS, 0, Math.PI * 2);
      ctx.fillStyle = '#080b0d'; ctx.fill();
      ctx.strokeStyle = '#725239'; ctx.lineWidth = 2; ctx.stroke();
    });
  }

  function roundRect(x, y, w, h, r) { ctx.beginPath(); ctx.roundRect(x, y, w, h, r); }

  function drawBalls() {
    balls.forEach(b => {
      if (b.pocketed) return;
      
      // رسم الكورة الأساسية
      ctx.beginPath(); ctx.arc(b.x, b.y, R, 0, Math.PI * 2);
      ctx.fillStyle = b.stripe ? '#f7f7f2' : b.color;
      ctx.fill();

      // لو مخططة، نرسم الشريط الملون
      if (b.stripe) {
        ctx.save();
        ctx.beginPath(); ctx.arc(b.x, b.y, R, 0, Math.PI * 2); ctx.clip();
        ctx.fillStyle = b.color;
        ctx.fillRect(b.x - R, b.y - R * 0.45, R * 2, R * 0.9); 
        ctx.restore();
      }

      // إطار الكورة
      ctx.beginPath(); ctx.arc(b.x, b.y, R, 0, Math.PI * 2);
      ctx.strokeStyle = '#ffffff88'; ctx.lineWidth = 1.5; ctx.stroke();

      // رقم الكورة
      if (b.number) {
        ctx.beginPath(); ctx.arc(b.x, b.y, 5, 0, Math.PI * 2);
        ctx.fillStyle = '#f7f7f2'; ctx.fill();
        ctx.fillStyle = '#222'; ctx.font = '7px Arial';
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(b.number, b.x, b.y + 0.5);
      }

      // دوران الكورة
      const speed = Math.hypot(b.vx, b.vy);
      if (speed > 2 && b.rotation) {
        const mr = R * 0.55;
        for (let k = 0; k < 2; k++) {
          const a = b.rotation + k * Math.PI;
          ctx.beginPath(); ctx.arc(b.x + Math.cos(a) * mr, b.y + Math.sin(a) * mr, 1.6, 0, Math.PI * 2);
          ctx.fillStyle = 'rgba(0,0,0,0.28)'; ctx.fill();
        }
      }
    });
  }

  function drawSparks() {
    sparks.forEach(s => {
      ctx.beginPath(); ctx.arc(s.x, s.y, 1.5, 0, Math.PI * 2);
      ctx.fillStyle = s.color; ctx.globalAlpha = s.life; ctx.fill();
      ctx.globalAlpha = 1;
    });
  }

  function drawCueStick() {
    if (running || ballInHand) return;
    const white = balls[0];
    if (!white || white.pocketed) return;

    const dx = Math.cos(angle), dy = Math.sin(angle);
    const startDistance = 38, stickLength = 115;
    const x1 = white.x - dx * startDistance, y1 = white.y - dy * startDistance;
    const x2 = white.x - dx * (startDistance + stickLength), y2 = white.y - dy * (startDistance + stickLength);

    ctx.save(); ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2);
    ctx.strokeStyle = '#d7b16b'; ctx.lineWidth = 5; ctx.lineCap = 'round'; ctx.stroke();
    ctx.restore();
  }

  function draw() {
    ctx.clearRect(0, 0, W, H);
    drawTable();

    if (!running && !ballInHand && !editingBalls) drawPredictions();

    trails.forEach(t => {
      ctx.beginPath(); ctx.moveTo(t.x1, t.y1); ctx.lineTo(t.x2, t.y2);
      ctx.strokeStyle = t.color || 'rgba(245,218,151,0.35)';
      ctx.globalAlpha = 0.35; ctx.lineWidth = 2; ctx.stroke(); ctx.globalAlpha = 1;
    });

    drawBalls();
    if (editingBalls && selectedBallIndex >= 0 && balls[selectedBallIndex]) {
      const selected = balls[selectedBallIndex];
      ctx.beginPath(); ctx.arc(selected.x, selected.y, R + 5, 0, Math.PI * 2);
      ctx.strokeStyle = '#ffdc78'; ctx.lineWidth = 2; ctx.stroke();
    }
    drawSparks();

    if (!running && !ballInHand && !editingBalls) drawAimAssist();
    if (!editingBalls) drawCueStick();

    if (ballInHand) {
      const white = balls[0];
      ctx.save();
      ctx.beginPath();
      ctx.arc(white.x, white.y, R + 4, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(255, 220, 120, ' + (0.4 + Math.sin(performance.now()/150)*0.4) + ')';
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.restore();
    }
  }

  // ==========================================
  // SHOOT & ANIMATION
  // ==========================================

  function shoot() {
    if (running || paused || ballInHand || editingBalls) return;
    const white = balls[0];
    if (!white || white.pocketed) return;

    const speed = getShotSpeed();
    white.vx = Math.cos(angle) * speed; white.vy = Math.sin(angle) * speed;
    white.spin = { x: spinState.x, y: spinState.y };

    running = true; paused = false;
    shotsTaken++; updateStats();
    document.getElementById('pauseBtn').textContent = 'إيقاف مؤقت';
    animationId = requestAnimationFrame(step);
  }

  function step() {
    if (!running || paused) { liveMode = false; animationId = null; return; }
    liveMode = true;

    balls.forEach(b => {
      if (b.pocketed) return;
      const sp = Math.hypot(b.vx, b.vy);
      if (sp > 1.5) {
        trails.push({ x1: b.x - b.vx * DT, y1: b.y - b.vy * DT, x2: b.x, y2: b.y, color: b.color });
      }
    });
    if (trails.length > 600) trails.splice(0, trails.length - 600);

    physicsStep(balls);
    updateSparks();

    const moving = balls.some(b => !b.pocketed && Math.hypot(b.vx, b.vy) >= 0.1);

    if (!moving) {
      running = false; liveMode = false; animationId = null;

      const white = balls[0];
      if (white && white.pocketed) {
        ballInHand = true;
        white.pocketed = false;
        white.x = 260; white.y = 250;
        white.vx = 0; white.vy = 0; white.spin = null; white.rotation = 0;
        hintText.textContent = "الكرة البيضاء وقعت! اضغط في أي مكان فاضي على الطاولة عشان تحطها.";
      } else {
        hintText.textContent = 'اسحب مؤشر الاتجاه حول الكرة البيضاء لتحديد زاوية الضربة.';
      }
      draw();
      return;
    }

    draw();
    animationId = requestAnimationFrame(step);
  }

  // ==========================================
  // IMAGE IMPORT + LIGHTWEIGHT BALL DETECTION
  // Runs only after the user selects an image; no screen capture or background processing.
  // ==========================================

  function rgbToHsv(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
    let h = 0;
    if (d) {
      if (max === r) h = ((g - b) / d) % 6;
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60; if (h < 0) h += 360;
    }
    return { h, s: max ? d / max : 0, v: max };
  }

  function detectTableRegion(data, width, height) {
    let minX = width, minY = height, maxX = -1, maxY = -1, count = 0;
    for (let y = 0; y < height; y += 2) {
      for (let x = 0; x < width; x += 2) {
        const i = (y * width + x) * 4;
        const hsv = rgbToHsv(data[i], data[i + 1], data[i + 2]);
        const felt = hsv.s > 0.22 && hsv.v > 0.12 &&
          ((hsv.h >= 68 && hsv.h <= 175) || (hsv.h >= 180 && hsv.h <= 255));
        if (!felt) continue;
        minX = Math.min(minX, x); minY = Math.min(minY, y);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); count++;
      }
    }
    if (count < width * height * 0.025 || maxX <= minX || maxY <= minY) return null;
    const padX = Math.round((maxX - minX) * 0.015);
    const padY = Math.round((maxY - minY) * 0.025);
    return {
      left: Math.max(0, minX - padX), top: Math.max(0, minY - padY),
      right: Math.min(width - 1, maxX + padX), bottom: Math.min(height - 1, maxY + padY)
    };
  }

  function sampleRgb(data, width, x, y) {
    x = Math.max(0, Math.min(width - 1, Math.round(x)));
    y = Math.max(0, Math.min(Math.floor(data.length / 4 / width) - 1, Math.round(y)));
    const i = (y * width + x) * 4;
    return [data[i], data[i + 1], data[i + 2]];
  }

  function detectBallCandidates(data, width, height, table) {
    const tableWidth = table.right - table.left;
    const radius = Math.max(5, Math.min(16, tableWidth / 82));
    const step = Math.max(2, Math.round(radius * 0.48));
    const candidates = [];
    const ringCount = 16;

    for (let y = table.top + radius * 1.35; y < table.bottom - radius * 1.35; y += step) {
      for (let x = table.left + radius * 1.35; x < table.right - radius * 1.35; x += step) {
        const center = sampleRgb(data, width, x, y);
        const inner = [0, 0, 0], outer = [0, 0, 0];
        let outerFelt = 0, innerFelt = 0;
        for (let k = 0; k < ringCount; k++) {
          const a = Math.PI * 2 * k / ringCount;
          const p1 = sampleRgb(data, width, x + Math.cos(a) * radius * 0.48, y + Math.sin(a) * radius * 0.48);
          const p2 = sampleRgb(data, width, x + Math.cos(a) * radius * 1.22, y + Math.sin(a) * radius * 1.22);
          for (let c = 0; c < 3; c++) { inner[c] += p1[c]; outer[c] += p2[c]; }
          const h1 = rgbToHsv(p1[0], p1[1], p1[2]);
          const h2 = rgbToHsv(p2[0], p2[1], p2[2]);
          if (h1.s > 0.22 && h1.v > 0.12 && ((h1.h >= 68 && h1.h <= 175) || (h1.h >= 180 && h1.h <= 255))) innerFelt++;
          if (h2.s > 0.22 && h2.v > 0.12 && ((h2.h >= 68 && h2.h <= 175) || (h2.h >= 180 && h2.h <= 255))) outerFelt++;
        }
        if (outerFelt < ringCount * 0.68 || innerFelt > ringCount * 0.55) continue;
        let diff = 0;
        for (let c = 0; c < 3; c++) diff += Math.abs(inner[c] - outer[c]) / ringCount;
        diff /= 3;
        const hsv = rgbToHsv(center[0], center[1], center[2]);
        if (diff < 25 || hsv.v < 0.12) continue;
        candidates.push({ x, y, radius, score: diff + (outerFelt / ringCount) * 8, color: center, hsv });
      }
    }

    candidates.sort((a, b) => b.score - a.score);
    const selected = [];
    for (const candidate of candidates) {
      if (selected.every(p => Math.hypot(p.x - candidate.x, p.y - candidate.y) > radius * 1.55)) {
        selected.push(candidate);
        if (selected.length >= 16) break;
      }
    }
    return selected;
  }

  function classifyDetectedBall(candidate, usedNumbers) {
    const { h, s, v } = candidate.hsv;
    if (v < 0.23) return BALL_TYPES.find(b => b.num === 8);
    if (s < 0.22 && v > 0.68) return { num: 0, color: '#f4f1e8', stripe: false };

    const palette = [
      { num: 1, hue: 48 }, { num: 2, hue: 212 }, { num: 3, hue: 4 },
      { num: 4, hue: 275 }, { num: 5, hue: 28 }, { num: 6, hue: 151 }, { num: 7, hue: 350 }
    ];
    const hueDistance = (a, b) => Math.min(Math.abs(a - b), 360 - Math.abs(a - b));
    const nearest = palette.reduce((best, item) =>
      hueDistance(h, item.hue) < hueDistance(h, best.hue) ? item : best, palette[0]);
    const localType = BALL_TYPES.find(b => b.num === nearest.num);
    const usedSolid = usedNumbers.has(nearest.num);
    const number = usedSolid ? nearest.num + 8 : nearest.num;
    usedNumbers.add(number);
    return { ...BALL_TYPES.find(b => b.num === number), color: localType.color };
  }

  function importTableImage(file) {
    if (!file || !file.type.startsWith('image/')) return;
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      try {
        const scale = Math.min(1, 1200 / image.width);
        const width = Math.max(1, Math.round(image.width * scale));
        const height = Math.max(1, Math.round(image.height * scale));
        const offscreen = document.createElement('canvas');
        offscreen.width = width; offscreen.height = height;
        const imageCtx = offscreen.getContext('2d', { willReadFrequently: true });
        imageCtx.drawImage(image, 0, 0, width, height);
        const pixels = imageCtx.getImageData(0, 0, width, height).data;
        const table = detectTableRegion(pixels, width, height);
        if (!table) {
          hintText.textContent = 'مش قادر أحدد لون الطاولة في الصورة. جرّب Screenshot أوضح والطاولة كاملة.';
          return;
        }
        const found = detectBallCandidates(pixels, width, height, table);
        if (found.length < 3) {
          hintText.textContent = 'الصورة اتفتحت، لكن اكتشاف الكرات مش واضح كفاية. استخدم وضع تعديل الكرات لتحريكها يدويًا.';
          return;
        }

        const mapX = x => bounds.left + (x - table.left) / (table.right - table.left) * (bounds.right - bounds.left);
        const mapY = y => bounds.top + (y - table.top) / (table.bottom - table.top) * (bounds.bottom - bounds.top);
        const usedNumbers = new Set();
        let cueBall = null;
        const objectBalls = [];
        found.forEach(candidate => {
          const type = classifyDetectedBall(candidate, usedNumbers);
          const ball = {
            x: mapX(candidate.x), y: mapY(candidate.y), vx: 0, vy: 0,
            color: type.color, number: type.num, stripe: !!type.stripe,
            pocketed: false, rotation: 0
          };
          if (type.num === 0 && !cueBall) cueBall = ball;
          else objectBalls.push(ball);
        });

        const previousCue = balls.find(b => b.number === 0 && !b.pocketed) || { ...cue };
        balls = [cueBall || { ...previousCue, vx: 0, vy: 0, pocketed: false }, ...objectBalls];
        trails = []; sparks = []; prediction = [];
        running = false; paused = false; ballInHand = false;
        if (animationId !== null) { cancelAnimationFrame(animationId); animationId = null; }
        editingBalls = true;
        editBtn.textContent = 'إنهاء التعديل';
        hintText.textContent = 'اتحدد ' + found.length + ' كرة بشكل مبدئي. راجع أماكنها وألوانها واسحب أي كرة لتصحيحها قبل التصويب.';
        draw();
      } catch (error) {
        hintText.textContent = 'حصلت مشكلة في تحليل الصورة. تقدر تستخدم تعديل الكرات يدويًا.';
      } finally {
        URL.revokeObjectURL(objectUrl);
        imageInput.value = '';
      }
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      imageInput.value = '';
      hintText.textContent = 'تعذر فتح الصورة. جرّب ملف JPG أو PNG.';
    };
    image.src = objectUrl;
  }

  // ==========================================
  // POINTER CONTROL
  // ==========================================

  function pointer(e) {
    const rect = canvas.getBoundingClientRect();
    const x = (e.clientX - rect.left) * W / rect.width;
    const y = (e.clientY - rect.top) * H / rect.height;
    return { x, y };
  }

  canvas.addEventListener('pointerdown', e => {
    initAudio();
    const pt = pointer(e);

    if (editingBalls && !running) {
      selectedBallIndex = -1;
      let nearestDistance = R * 2.2;
      balls.forEach((ball, index) => {
        if (ball.pocketed) return;
        const distance = Math.hypot(pt.x - ball.x, pt.y - ball.y);
        if (distance < nearestDistance) { nearestDistance = distance; selectedBallIndex = index; }
      });
      if (selectedBallIndex >= 0) canvas.setPointerCapture(e.pointerId);
      draw();
      return;
    }

    if (ballInHand) {
      const overlap = balls.some(b => !b.pocketed && b.number !== 0 && Math.hypot(pt.x - b.x, pt.y - b.y) < DIAMETER + 2);
      const outsideBounds = pt.x - R < bounds.left || pt.x + R > bounds.right || pt.y - R < bounds.top || pt.y + R > bounds.bottom;

      if (!overlap && !outsideBounds) {
        const white = balls[0];
        white.x = pt.x; white.y = pt.y;
        ballInHand = false;
        hintText.textContent = 'اسحب مؤشر الاتجاه حول الكرة البيضاء لتحديد زاوية الضربة.';
        draw();
      } else {
        hintText.textContent = "مكان غير صالح! اختار مكان فاضي بعيد عن الكور التانية.";
      }
      return;
    }

    if (running || paused) return;
    dragging = true;
    canvas.setPointerCapture(e.pointerId);
    angle = Math.atan2(pt.y - balls[0].y, pt.x - balls[0].x);
    draw();
  });

  canvas.addEventListener('pointermove', e => {
    if (editingBalls && selectedBallIndex >= 0) {
      const pt = pointer(e);
      const ball = balls[selectedBallIndex];
      ball.x = Math.max(bounds.left + R, Math.min(bounds.right - R, pt.x));
      ball.y = Math.max(bounds.top + R, Math.min(bounds.bottom - R, pt.y));
      draw();
      return;
    }

    if (ballInHand) {
      const pt = pointer(e);
      balls[0].x = pt.x; balls[0].y = pt.y; 
      draw();
      return;
    }

    if (dragging) {
      const pt = pointer(e);
      angle = Math.atan2(pt.y - balls[0].y, pt.x - balls[0].x);
      draw();
    }
  });

  canvas.addEventListener('pointerup', () => { dragging = false; selectedBallIndex = -1; });
  canvas.addEventListener('pointercancel', () => { dragging = false; selectedBallIndex = -1; });

  // ==========================================
  // SPIN & POWER CONTROLS
  // ==========================================

  function handleSpinInput(e) {
    const rect = spinPad.getBoundingClientRect();
    const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
    let dx = (e.clientX - cx) / (rect.width / 2);
    let dy = -(e.clientY - cy) / (rect.height / 2);

    const len = Math.hypot(dx, dy);
    if (len > 1) { dx /= len; dy /= len; }

    spinState.x = Math.max(-1, Math.min(1, dx));
    spinState.y = Math.max(-1, Math.min(1, dy));
    updateSpinUI();
    if (!running && !paused) draw();
  }

  let spinDragging = false;
  spinPad.addEventListener('pointerdown', (e) => { spinDragging = true; spinPad.setPointerCapture(e.pointerId); handleSpinInput(e); });
  spinPad.addEventListener('pointermove', (e) => { if (spinDragging) handleSpinInput(e); });
  spinPad.addEventListener('pointerup', () => { spinDragging = false; });
  spinPad.addEventListener('pointercancel', () => { spinDragging = false; });

  powerInput.addEventListener('input', () => {
    powerValue.textContent = powerInput.value + '%';
    draw();
  });

  // ==========================================
  // BUTTONS
  // ==========================================

  document.getElementById('shootBtn').addEventListener('click', () => { initAudio(); shoot(); });
  document.getElementById('resetBtn').addEventListener('click', reset);

  editBtn.addEventListener('click', () => {
    if (running) return;
    editingBalls = !editingBalls;
    selectedBallIndex = -1;
    editBtn.textContent = editingBalls ? 'إنهاء التعديل' : 'تعديل الكرات';
    hintText.textContent = editingBalls
      ? 'اسحب أي كرة لمكانها الصحيح. اضغط «إنهاء التعديل» للعودة للتصويب.'
      : 'وضع التصويب جاهز. اسحب حول الكرة البيضاء لتحديد الاتجاه.';
    draw();
  });
  imageBtn.addEventListener('click', () => imageInput.click());
  imageInput.addEventListener('change', event => importTableImage(event.target.files && event.target.files[0]));
  document.getElementById('clearBtn').addEventListener('click', () => { trails = []; draw(); });
  
  document.getElementById('pauseBtn').addEventListener('click', () => {
    if (!running && !paused) return;
    paused = !paused;
    document.getElementById('pauseBtn').textContent = paused ? 'استكمال' : 'إيقاف مؤقت';

    if (!paused) { animationId = requestAnimationFrame(step); } 
    else if (animationId !== null) { cancelAnimationFrame(animationId); animationId = null; liveMode = false; }
  });

  // ==========================================
  // START ENGINE
  // ==========================================

  powerValue.textContent = getPower() + '%';
  updateSpinUI();
  reset();

})();