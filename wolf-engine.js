/*
 * WOLF BILLIARDS — Headless Aiming Engine (wolf-engine.js)
 * ==================================================================
 * محرك الحسابات المستقل — مستخرج من فيزياء app.js
 * (نفس الثوابت ونفس المعادلات: RESTITUTION / WALL_RESTITUTION / FRICTION /
 *  DT / SUBSTEPS / MAX_STEPS + نفس منطق checkPocket / handleWalls /
 *  resolveBallPairCollision / physicsStep).
 *
 * تحسين رقمي وحيد وموثق فوق فيزياء app.js:
 *   ترجيع زمني (CCD) داخل الخطوة الفرعية إلى لحظة التلامس التام قبل
 *   تبادل الدفعة. بدونه تتشوه زاوية التصادم حتى ~4° بسبب قفزات الخطوة
 *   (الخطأ كان يتراكم على المسافات البعيدة ويفوّت فم الجيب).
 *   المعادلات الفيزيائية نفسها لم تتغير إطلاقًا.
 *
 * الفرق الأساسي عن app.js: هذا الملف لا يعتمد على DOM أو Canvas إطلاقًا.
 * يدخل إليه "حالة الطاولة" كبيانات (إحداثيات موحدة)، ويخرج منه
 * مسارات التصويب كبيانات — ليستخدمها أي واجهة (محاكاة أو Overlay فوق اللعبة).
 *
 * نظام الإحداثيات الموحد (Normalized Table Space):
 *   - مساحة اللعب فقط (بدون الحواف الخشبية): x من 0 إلى 2، y من 0 إلى 1.
 *   - (0,0) = الزاوية العلوية اليسرى لمساحة اللعب، والمحور y لأسفل.
 *   - الوحدة متساوية الاتجاه (isotropic): وحدة واحدة = نصف عرض مساحة اللعب.
 *   - نصف قطر الكرة الافتراضي = 11/402 ≈ 0.0274 (مطابق لنسبة app.js).
 *   - الجيوب الستة الافتراضية:
 *       (0,0) (1,0) (2,0)
 *       (0,1) (1,1) (2,1)
 *     بنصف التقاط مطابق لـ app.js (الزوايا 22px والوسطية 15px بعد التحويل).
 *
 * التحويل من إطار الرؤية (أبعاد مستطيل الطاولة بالبكسل على الشاشة):
 *   xN = (bx - rect.left) * 2 / rect.width
 *   yN = (by - rect.top) / rect.height
 *   ballRadiusN = 2 * ballRadiusPx / rect.width
 *   اتجاه بالبكسل (dxPx, dyPx) → اتجاه موحد: (dxPx * 2 / rect.width, dyPx / rect.height) ثم تطبيع.
 *
 * التحويل إلى الشاشة مرة أخرى (للرسم في الـ Overlay):
 *   px = rect.left + xN * rect.width / 2
 *   py = rect.top  + yN * rect.height
 *
 * الاستخدام:
 *   const result = WolfEngine.solve(tableState);
 *   const sim    = WolfEngine.simulate(tableState, {x: 0, y: -1}, 55);
 * يعمل في المتصفح/WebView (window.WolfEngine) وفي Node (module.exports).
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof root !== 'undefined') root.WolfEngine = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';

  // ==========================================
  // ثوابت الفيزياء — منقولة كما هي من app.js
  // ==========================================
  var RESTITUTION = 0.96;      // ارتداد كرة-كرة
  var WALL_RESTITUTION = 0.92; // ارتداد كرة-حافة
  var FRICTION = 0.985;        // احتكاك لكل خطوة 1/60 ثانية
  var DT = 1 / 60;
  var SUBSTEPS = 2;
  var MAX_STEPS = 1200;
  var DEFAULT_POWER = 55;

  // ==========================================
  // فضاء المحاكاة الداخلي — نفس أبعاد طاولة app.js
  // ==========================================
  var SIM = { left: 48, right: 852, top: 48, bottom: 452, R: 11 };
  var SIM_SX = (SIM.right - SIM.left) / 2; // 402 بكسل لكل وحدة موحدة في x
  var SIM_SY = (SIM.bottom - SIM.top) / 1; // 404 بكسل لكل وحدة موحدة في y

  var DEFAULT_BALL_RADIUS = SIM.R / SIM_SX;  // ≈ 0.02736 وحدة
  var CORNER_POCKET_RADIUS = 22 / SIM_SX;    // نفس نصف التقاط الجيوب في app.js
  var SIDE_POCKET_RADIUS = 15 / SIM_SX;

  var MAX_CUT_ANGLE_DEG = 80; // أقصى زاوية قطع يعتبرها المحرك قابلة للتنفيذ

  function defaultPockets() {
    return [
      { x: 0, y: 0, radius: CORNER_POCKET_RADIUS },
      { x: 1, y: 0, radius: SIDE_POCKET_RADIUS },
      { x: 2, y: 0, radius: CORNER_POCKET_RADIUS },
      { x: 0, y: 1, radius: CORNER_POCKET_RADIUS },
      { x: 1, y: 1, radius: SIDE_POCKET_RADIUS },
      { x: 2, y: 1, radius: CORNER_POCKET_RADIUS }
    ];
  }

  // ==========================================
  // تحويلات الإحداثيات (موحد ↔ محاكاة)
  // ==========================================
  function toSimPoint(p) {
    return { x: SIM.left + p.x * SIM_SX, y: SIM.top + p.y * SIM_SY };
  }
  function toNormPoint(p) {
    return { x: (p.x - SIM.left) / SIM_SX, y: (p.y - SIM.top) / SIM_SY };
  }
  function toNormPath(points) {
    return points.map(function (p) { return toNormPoint(p); });
  }
  // اتجاه في الفضاء الموحد → زاوية في فضاء المحاكاة
  function normDirToSimAngle(d) {
    return Math.atan2(d.y * SIM_SY, d.x * SIM_SX);
  }
  // زاوية في فضاء المحاكاة → اتجاه موحد (متجه طول الوحدة)
  function simAngleToNormDir(a) {
    var dx = Math.cos(a) / SIM_SX, dy = Math.sin(a) / SIM_SY;
    var len = Math.hypot(dx, dy) || 1;
    return { x: dx / len, y: dy / len };
  }
  function normalizeDir(d) {
    var len = Math.hypot(d.x, d.y);
    if (!len || !isFinite(len)) return null;
    return { x: d.x / len, y: d.y / len };
  }

  // ==========================================
  // بناء حالة المحاكاة من بيانات الإدخال الموحدة
  // ==========================================
  function buildSimState(state) {
    var warnings = [];
    if (!state || typeof state !== 'object') return { error: 'حالة الطاولة مفقودة' };

    var ballRadius = (typeof state.ballRadius === 'number' && state.ballRadius > 0)
      ? state.ballRadius : DEFAULT_BALL_RADIUS;
    if (ballRadius !== DEFAULT_BALL_RADIUS)
      warnings.push('نصف قطر الكرة مختلف عن الافتراضي (' + ballRadius.toFixed(4) + ')');

    var R = ballRadius * SIM_SX;              // نصف القطر داخل فضاء المحاكاة
    var speedScale = R / SIM.R;               // حفظ ديناميكية الضربة عند تغير الحجم

    var pockets = (state.pockets && state.pockets.length)
      ? state.pockets.map(function (p) {
          var s = toSimPoint(p);
          return { x: s.x, y: s.y, radius: ((typeof p.radius === 'number' && p.radius > 0) ? p.radius : CORNER_POCKET_RADIUS) * SIM_SX };
        })
      : defaultPockets().map(function (p) {
          var s = toSimPoint(p);
          return { x: s.x, y: s.y, radius: p.radius * SIM_SX };
        });

    var rawBalls = Array.isArray(state.balls) ? state.balls : [];
    var balls = rawBalls.map(function (b, i) {
      var s = toSimPoint(clampToPlayArea(b, R));
      return {
        x: s.x, y: s.y, vx: 0, vy: 0,
        number: (typeof b.number === 'number') ? b.number : (i + 1),
        color: b.color || null,
        pocketed: false, pocketIndex: -1, spin: null
      };
    });

    if (!state.cueBall || typeof state.cueBall.x !== 'number' || typeof state.cueBall.y !== 'number') {
      return { error: 'موقع الكرة البيضاء مطلوب (cueBall: {x, y})' };
    }
    var cueSim = toSimPoint(clampToPlayArea(state.cueBall, R));
    var cue = {
      x: cueSim.x, y: cueSim.y, vx: 0, vy: 0,
      number: 0, color: '#f4f1e8', pocketed: false, pocketIndex: -1, spin: null
    };

    return {
      ok: true, warnings: warnings,
      R: R, diameter: R * 2, speedScale: speedScale,
      pockets: pockets, balls: balls, cue: cue,
      power: (typeof state.power === 'number') ? Math.max(10, Math.min(100, state.power)) : DEFAULT_POWER,
      spin: (state.spin && typeof state.spin.x === 'number' && typeof state.spin.y === 'number')
        ? { x: state.spin.x, y: state.spin.y } : null,
      maxAims: (typeof state.maxAims === 'number' && state.maxAims > 0) ? Math.floor(state.maxAims) : 3
    };
  }

  function clampToPlayArea(p, R) {
    var left = 0, right = 2, top = 0, bottom = 1; // حدود مساحة اللعب في الفضاء الموحد
    var marginN = R / SIM_SX;
    return {
      x: Math.max(-marginN, Math.min(2 + marginN, p.x)),
      y: Math.max(-marginN, Math.min(1 + marginN, p.y))
    };
  }

  // ==========================================
  // فيزياء app.js — منقولة كما هي (بدون صوت/شرر/DOM)
  // ==========================================

  // checkPocket من app.js مع تسجيل رقم الجيب
  function checkPocket(ball, pockets) {
    for (var j = 0; j < pockets.length; j++) {
      var pocket = pockets[j];
      var distance = Math.hypot(ball.x - pocket.x, ball.y - pocket.y);
      if (distance < pocket.radius) {
        ball.pocketed = true;
        ball.pocketIndex = j;
        ball.x = pocket.x; ball.y = pocket.y;
        ball.vx = 0; ball.vy = 0;
        return true;
      }
    }
    return false;
  }

  // isNearPocket من app.js (+5 بكسل)
  function isNearPocket(x, y, pockets) {
    for (var j = 0; j < pockets.length; j++) {
      var p = pockets[j];
      if (Math.hypot(x - p.x, y - p.y) < p.radius + 5) return true;
    }
    return false;
  }

  // handleWalls من app.js
  function handleWalls(ball, sim) {
    if (ball.pocketed) return;
    var R = sim.R;
    var left = SIM.left, right = SIM.right, top = SIM.top, bottom = SIM.bottom;

    if (ball.x - R < left && !isNearPocket(ball.x, ball.y, sim.pockets)) {
      ball.x = left + R; ball.vx = Math.abs(ball.vx) * WALL_RESTITUTION;
    }
    if (ball.x + R > right && !isNearPocket(ball.x, ball.y, sim.pockets)) {
      ball.x = right - R; ball.vx = -Math.abs(ball.vx) * WALL_RESTITUTION;
    }
    if (ball.y - R < top && !isNearPocket(ball.x, ball.y, sim.pockets)) {
      ball.y = top + R; ball.vy = Math.abs(ball.vy) * WALL_RESTITUTION;
    }
    if (ball.y + R > bottom && !isNearPocket(ball.x, ball.y, sim.pockets)) {
      ball.y = bottom - R; ball.vy = -Math.abs(ball.vy) * WALL_RESTITUTION;
    }
  }

  // resolveBallPairCollision من app.js (مع نقل الـ spin كما هو)
  // + تحسين رقمي وحيد وموثق: ترجيع زمني (CCD) داخل الخطوة الفرعية
  //   إلى لحظة التلامس التام قبل تبادل الدفعة، حتى لا تتشوه زاوية
  //   التصادم بسبب قفزات الخطوة (خطأ وصل 3.9° ويُفوّت الجيوب البعيدة).
  //   المعادلات نفسها؛ التصحيح للمواضع لحظة الاكتشاف فقط.
  function resolveBallPairCollision(a, b, sim) {
    if (a.pocketed || b.pocketed) return false;
    var R = sim.R, DIAMETER = sim.diameter;

    var dx = b.x - a.x, dy = b.y - a.y;
    var distance = Math.hypot(dx, dy);
    if (distance >= DIAMETER) return false;

    var nx = 0, ny = 0;
    if (distance < 0.0001) {
      var relVx = b.vx - a.vx, relVy = b.vy - a.vy;
      var fallbackX = relVx || 1, fallbackY = relVy || 0;
      var fallbackLength = Math.hypot(fallbackX, fallbackY) || 1;
      nx = fallbackX / fallbackLength; ny = fallbackY / fallbackLength;
      distance = 0.0001; dx = nx * distance; dy = ny * distance;
    } else {
      nx = dx / distance; ny = dy / distance;
    }

    var relativeVelocityX = b.vx - a.vx;
    var relativeVelocityY = b.vy - a.vy;
    var velocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;

    // ---- CCD: ترجيع المواضع إلى لحظة التلامس التام (|a-b| = القطر) ----
    var rewindT = 0;
    if (velocityAlongNormal < 0) {
      var relSpeed = Math.hypot(relativeVelocityX, relativeVelocityY);
      if (relSpeed > 0.0001) {
        var ux = relativeVelocityX / relSpeed, uy = relativeVelocityY / relSpeed;
        var sDotU = dx * ux + dy * uy;
        var root = sDotU * sDotU + DIAMETER * DIAMETER - distance * distance;
        if (root > 0) {
          // mu = المسافة النسبية التي يجب الرجوع بها على خط الحركة
          var mu = sDotU + Math.sqrt(root);
          if (mu > 0 && isFinite(mu)) {
            rewindT = mu / relSpeed;
            a.x -= a.vx * rewindT; a.y -= a.vy * rewindT;
            b.x -= b.vx * rewindT; b.y -= b.vy * rewindT;
            dx = b.x - a.x; dy = b.y - a.y;
            distance = Math.hypot(dx, dy);
            if (distance > 0.0001) {
              nx = dx / distance; ny = dy / distance;
              velocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;
            }
          }
        }
      }
    }

    var overlap = DIAMETER - distance;
    if (overlap > 0) {
      var separation = overlap * 0.5;
      a.x -= nx * separation; a.y -= ny * separation;
      b.x += nx * separation; b.y += ny * separation;

      var clampBall = function (ball) {
        if (ball.pocketed || isNearPocket(ball.x, ball.y, sim.pockets)) return;
        if (ball.x - R < SIM.left) ball.x = SIM.left + R;
        if (ball.x + R > SIM.right) ball.x = SIM.right - R;
        if (ball.y - R < SIM.top) ball.y = SIM.top + R;
        if (ball.y + R > SIM.bottom) ball.y = SIM.bottom - R;
      };
      clampBall(a); clampBall(b);
    }

    if (velocityAlongNormal >= 0) return false;

    var impulse = (-velocityAlongNormal) * (1 + RESTITUTION) / 2;
    a.vx -= impulse * nx; a.vy -= impulse * ny;
    b.vx += impulse * nx; b.vy += impulse * ny;

    // نقل الدوران (follow/throw) — نفس معادلات app.js
    var cueBall = a.number === 0 ? a : (b.number === 0 ? b : null);
    if (cueBall && cueBall.spin && (Math.abs(cueBall.spin.x) > 0.01 || Math.abs(cueBall.spin.y) > 0.01)) {
      var toObjX = cueBall === a ? nx : -nx;
      var toObjY = cueBall === a ? ny : -ny;

      var followStrength = cueBall.spin.y * 120 * sim.speedScale;
      cueBall.vx += toObjX * followStrength;
      cueBall.vy += toObjY * followStrength;

      var tanX = -toObjY, tanY = toObjX;
      var objBall = cueBall === a ? b : a;
      var throwStrength = cueBall.spin.x * 35 * sim.speedScale;
      objBall.vx += tanX * throwStrength;
      objBall.vy += tanY * throwStrength;

      cueBall.spin.x *= 0.4; cueBall.spin.y *= 0.4;
    }

    // ---- استرجاع زمن الخطوة المتبقي بعد تبادل الدفعة (إتمام CCD) ----
    if (rewindT > 0) {
      a.x += a.vx * rewindT; a.y += a.vy * rewindT;
      b.x += b.vx * rewindT; b.y += b.vy * rewindT;
    }
    return true;
  }

  function resolveBallCollisions(list, sim) {
    var maxPasses = 6;
    for (var pass = 0; pass < maxPasses; pass++) {
      var collisionDetected = false;
      for (var i = 0; i < list.length; i++) {
        var a = list[i];
        if (a.pocketed) continue;
        for (var j = i + 1; j < list.length; j++) {
          var b = list[j];
          if (b.pocketed) continue;
          if (resolveBallPairCollision(a, b, sim)) collisionDetected = true;
        }
      }
      if (!collisionDetected) break;
    }
  }

  // physicsStep من app.js
  function physicsStep(list, sim) {
    var subDT = DT / SUBSTEPS;
    for (var sub = 0; sub < SUBSTEPS; sub++) {
      for (var i = 0; i < list.length; i++) {
        var b = list[i];
        if (b.pocketed) continue;
        b.x += b.vx * subDT; b.y += b.vy * subDT;
        if (checkPocket(b, sim.pockets)) continue;
        handleWalls(b, sim);

        var frictionFactor = Math.pow(FRICTION, subDT / DT);
        b.vx *= frictionFactor; b.vy *= frictionFactor;
        if (b.spin) {
          var spinDecay = Math.pow(0.995, subDT / DT);
          b.spin.x *= spinDecay; b.spin.y *= spinDecay;
        }
        var speed = Math.hypot(b.vx, b.vy);
        if (speed < 0.1) { b.vx = 0; b.vy = 0; }
      }
      resolveBallCollisions(list, sim);
    }
  }

  // getShotSpeed من app.js مع معامل تكبير عند تغير نصف القطر
  function shotSpeed(power, sim) {
    return (260 + power * 8.5) * sim.speedScale;
  }

  // ==========================================
  // Ray-cast: أول اصطدام على مسار مستقيم
  // (نفس رياضيات findTargetBall في app.js — تقاطع شعاع/دائرة بنصف قطر 2R)
  // ==========================================
  function rayFirstHit(origin, dir, balls, sim, options) {
    options = options || {};
    var envelope = sim.diameter;
    var dx = dir.x, dy = dir.y;
    var best = null;

    for (var i = 0; i < balls.length; i++) {
      var b = balls[i];
      if (b.pocketed) continue;
      if (options.excludeCue && b.number === 0) continue;
      if (typeof options.excludeIndex === 'number' && i === options.excludeIndex) continue;

      var ox = origin.x - b.x, oy = origin.y - b.y;
      var dot = ox * dx + oy * dy;
      var discriminant = dot * dot - (ox * ox + oy * oy - envelope * envelope);
      if (discriminant < 0) continue;
      var t = -dot - Math.sqrt(discriminant);
      if (t > 0.01 && (!best || t < best.dist)) {
        best = { type: 'ball', index: i, dist: t };
      }
    }

    if (options.cushions) {
      var R = sim.R;
      var cushionT = Infinity;
      if (dx > 0.0001) cushionT = Math.min(cushionT, (SIM.right - R - origin.x) / dx);
      else if (dx < -0.0001) cushionT = Math.min(cushionT, (SIM.left + R - origin.x) / dx);
      if (dy > 0.0001) cushionT = Math.min(cushionT, (SIM.bottom - R - origin.y) / dy);
      else if (dy < -0.0001) cushionT = Math.min(cushionT, (SIM.top + R - origin.y) / dy);
      if (isFinite(cushionT) && cushionT > 0.01 && (!best || cushionT < best.dist)) {
        best = { type: 'cushion', index: -1, dist: cushionT };
      }
    }

    if (!best) return null;
    best.point = { x: origin.x + dx * best.dist, y: origin.y + dy * best.dist };
    return best;
  }

  // ==========================================
  // محاكاة ضربة كاملة (مثل simulatePrediction في app.js)
  // ==========================================
  function simulateShot(sim, simAngle, power, spin) {
    var list = [sim.cue].concat(sim.balls).map(function (b) {
      return {
        x: b.x, y: b.y, vx: b.vx, vy: b.vy,
        number: b.number, color: b.color,
        pocketed: b.pocketed, pocketIndex: b.pocketIndex,
        spin: b.spin ? { x: b.spin.x, y: b.spin.y } : null
      };
    });
    var cue = list[0];
    var speed = shotSpeed(power, sim);
    cue.vx = Math.cos(simAngle) * speed;
    cue.vy = Math.sin(simAngle) * speed;
    if (spin) cue.spin = { x: spin.x, y: spin.y };

    var paths = list.map(function (b) {
      return b.pocketed ? [] : [{ x: b.x, y: b.y }];
    });

    for (var step = 0; step < MAX_STEPS; step++) {
      physicsStep(list, sim);
      var movingCount = 0;
      for (var i = 0; i < list.length; i++) {
        var b = list[i];
        if (!b.pocketed) {
          var sp = Math.hypot(b.vx, b.vy);
          if (sp >= 0.1) {
            movingCount++;
            if (step % 2 === 0) paths[i].push({ x: b.x, y: b.y });
          }
        }
      }
      if (movingCount === 0) break;
    }

    var outcome = list.map(function (b, i) {
      if (!b.pocketed) paths[i].push({ x: b.x, y: b.y });
      return { pocketed: b.pocketed, pocketIndex: b.pocketIndex, x: b.x, y: b.y };
    });

    return { paths: paths, outcome: outcome };
  }

  // ==========================================
  // حلّال التصويب (Aim Solver)
  // لكل كرة هدف × كل جيب: هندسة الكرة الشبحية (Ghost Ball)
  // + فحص المسارات الخالية + تحقق كامل بالمحاكاة الفيزيائية.
  // ==========================================
  function solveAims(sim) {
    var aims = [];
    var cue = sim.cue;
    var R = sim.R;

    for (var i = 0; i < sim.balls.length; i++) {
      var ball = sim.balls[i];
      if (ball.pocketed) continue;

      for (var j = 0; j < sim.pockets.length; j++) {
        var pocket = sim.pockets[j];

        // اتجاه سير الكرة الهدف نحو الجيب
        var bpX = pocket.x - ball.x, bpY = pocket.y - ball.y;
        var bpLen = Math.hypot(bpX, bpY);
        if (bpLen < 1) continue; // الكرة فوق الجيب تمامًا
        var dirBP = { x: bpX / bpLen, y: bpY / bpLen };

        // موضع الكرة الشبحية: مركز الكرة البيضاء لحظة التلامس
        var ghost = { x: ball.x - dirBP.x * sim.diameter, y: ball.y - dirBP.y * sim.diameter };

        // يجب أن تكون الشبحية في مكان يمكن للبيضاء الوصول إليه
        if (!isReachablePosition(ghost, sim)) continue;

        // اتجاه البيضاء نحو الشبحية
        var cgX = ghost.x - cue.x, cgY = ghost.y - cue.y;
        var cgLen = Math.hypot(cgX, cgY);
        if (cgLen < 1) continue;
        var dirCG = { x: cgX / cgLen, y: cgY / cgLen };

        // زاوية القطع: بين اتجاه وصول البيضاء واتجاه انطلاق الهدف
        var cosCut = dirCG.x * dirBP.x + dirCG.y * dirBP.y;
        var cutDeg = Math.acos(Math.max(-1, Math.min(1, cosCut))) * 180 / Math.PI;
        if (cutDeg > MAX_CUT_ANGLE_DEG) continue;

        // لا تقف كرة أخرى مكان الشبحية
        var ghostBlocked = sim.balls.some(function (other, k) {
          return k !== i && !other.pocketed &&
            Math.hypot(other.x - ghost.x, other.y - ghost.y) < sim.diameter * 0.95;
        });
        if (ghostBlocked) continue;

        // مسار البيضاء يجب أن يصل للهدف أولًا وعند الشبحية بالضبط
        var cueHit = rayFirstHit(cue, dirCG, sim.balls, sim, { excludeCue: false });
        if (!cueHit || cueHit.type !== 'ball' || cueHit.index !== i) continue;
        if (Math.abs(cueHit.dist - cgLen) > R * 0.75) continue;

        // مسار الهدف نحو الجيب يجب ألا تعترضه كرة أخرى
        var objHit = rayFirstHit(ball, dirBP, sim.balls, sim, { excludeIndex: i });
        if (objHit && objHit.type === 'ball' && objHit.dist < bpLen - R * 0.5) continue;

        // مرشح هندسي سليم → تحقق بمحاكاة فيزيائية كاملة على عدة قوى
        var candidate = evaluateCandidate(sim, i, j, ghost, dirCG, cutDeg, cgLen, bpLen);
        if (candidate) aims.push(candidate);
      }
    }

    aims.sort(function (a, b) { return b.score - a.score; });

    var makeable = aims.filter(function (a) { return a.makeable; });
    var chosen = makeable.length ? makeable : aims;
    var limited = chosen.slice(0, sim.maxAims);

    // تحويل النتائج إلى الفضاء الموحد
    return limited.map(function (a) {
      return {
        ballIndex: a.ballIndex,
        ballNumber: a.ballNumber,
        ballPos: toNormPoint(a.ballPos),
        pocketIndex: a.pocketIndex,
        pocketPos: toNormPoint(a.pocketPos),
        ghost: toNormPoint(a.ghost),
        cutAngleDeg: Math.round(a.cutDeg * 10) / 10,
        aimDirection: a.aimDirection,       // اتجاه تصويب البيضاء (فضاء موحد)
        makeable: a.makeable,               // المحاكاة أكدت إدخال الجيب
        scratch: a.scratch,                 // البيضاء وقعت أيضًا
        power: a.power,
        quality: Math.round(a.quality * 100) / 100,
        cuePath: toNormPath(a.cuePath),     // مسار البيضاء الفعلي بعد المحاكاة
        objectPath: toNormPath(a.objectPath)// مسار الكرة الهدف بعد المحاكاة
      };
    });
  }

  function isReachablePosition(p, sim) {
    var R = sim.R;
    var inside = p.x >= SIM.left - R * 0.5 && p.x <= SIM.right + R * 0.5 &&
                 p.y >= SIM.top - R * 0.5 && p.y <= SIM.bottom + R * 0.5;
    if (inside) return true;
    // قرب فتحة جيب يسمح بالوصول (نفس استثناء app.js في handleWalls)
    return isNearPocket(p.x, p.y, sim.pockets);
  }

  function evaluateCandidate(sim, ballIndex, pocketIndex, ghost, dirCG, cutDeg, cueDist, objDist) {
    var powers = [];
    var preferred = sim.power;
    [preferred, 80, 45].forEach(function (p) {
      if (powers.indexOf(p) === -1) powers.push(p);
    });

    var simAngle = normDirToSimAngle(dirCG); // dirCG فضاء محاكاة أصلًا هنا
    var best = null;

    for (var k = 0; k < powers.length; k++) {
      var power = powers[k];
      var res = simulateShot(sim, simAngle, power, sim.spin);
      var out = res.outcome[ballIndex + 1];
      var cueOut = res.outcome[0];

      var potted = out.pocketed;
      var inTarget = potted && out.pocketIndex === pocketIndex;
      var scratch = cueOut.pocketed;

      var score = 0;
      score += inTarget ? 100 : (potted ? 55 : 0);
      score -= cutDeg * 0.55;
      score -= (cueDist + objDist) * 0.004;
      if (scratch) score -= 80;

      if (!best || score > best.score) {
        var totalNormDist = (cueDist + objDist) / SIM_SX;
        var quality = Math.max(0, (1 - cutDeg / MAX_CUT_ANGLE_DEG)) *
          (1 - 0.18 * Math.min(1, totalNormDist / 2.4));
        if (!potted) quality *= 0.25;
        if (scratch) quality *= 0.2;
        best = {
          ballIndex: ballIndex,
          ballNumber: sim.balls[ballIndex].number,
          ballPos: { x: sim.balls[ballIndex].x, y: sim.balls[ballIndex].y },
          pocketIndex: pocketIndex,
          pocketPos: { x: sim.pockets[pocketIndex].x, y: sim.pockets[pocketIndex].y },
          ghost: ghost,
          cutDeg: cutDeg,
          aimDirection: simAngleToNormDir(simAngle),
          makeable: potted,
          scratch: scratch,
          power: power,
          score: score,
          quality: Math.max(0, Math.min(1, quality)),
          cuePath: res.paths[0],
          objectPath: res.paths[ballIndex + 1]
        };
      }
    }
    return best;
  }

  // ==========================================
  // تقييم ضربة حالية (اتجاه العصا المقدّر من الرؤية)
  // ==========================================
  function evaluateCurrentShot(sim, state) {
    var dir = null;
    if (state.aimDirection && typeof state.aimDirection.x === 'number') {
      dir = normalizeDir(state.aimDirection);
    } else if (typeof state.aimAngle === 'number') {
      dir = normalizeDir({ x: Math.cos(state.aimAngle), y: Math.sin(state.aimAngle) });
    }
    if (!dir) return null;

    var simAngle = normDirToSimAngle(dir);
    var simDir = { x: Math.cos(simAngle), y: Math.sin(simAngle) };

    var hit = rayFirstHit(sim.cue, simDir, sim.balls, sim, { excludeCue: false, cushions: true });

    var firstHit = null;
    var objectDirection = null;
    if (hit && hit.type === 'ball') {
      var target = sim.balls[hit.index];
      firstHit = {
        ballIndex: hit.index,
        ballNumber: target.number,
        ballPos: toNormPoint({ x: target.x, y: target.y }),
        contact: toNormPoint(hit.point) // مركز البيضاء لحظة التلامس
      };
      var objAngle = Math.atan2(target.y - hit.point.y, target.x - hit.point.x);
      objectDirection = simAngleToNormDir(objAngle);
    }

    var res = simulateShot(sim, simAngle, sim.power, sim.spin);
    var pocketedBalls = [];
    res.outcome.forEach(function (o, i) {
      if (o.pocketed && i > 0) pocketedBalls.push({ ballIndex: i - 1, pocketIndex: o.pocketIndex });
    });

    return {
      aimDirection: dir,
      firstHit: firstHit,
      objectDirection: objectDirection,
      targetPocketed: firstHit ? res.outcome[firstHit.ballIndex + 1].pocketed : false,
      cuePocketed: res.outcome[0].pocketed,
      pocketedBalls: pocketedBalls,
      cuePath: toNormPath(res.paths[0]),
      targetPath: firstHit ? toNormPath(res.paths[firstHit.ballIndex + 1]) : null
    };
  }

  // ==========================================
  // الواجهة العامة
  // ==========================================
  function solve(state) {
    var sim = buildSimState(state);
    if (!sim.ok) return { ok: false, error: sim.error, aims: [], currentShot: null };

    return {
      ok: true,
      warnings: sim.warnings,
      meta: {
        normalizedSpace: 'x∈[0,2], y∈[0,1] — مساحة اللعب فقط، الوحدة = نصف عرض مساحة اللعب',
        ballRadius: sim.R / SIM_SX,
        ballCount: sim.balls.length,
        pockets: defaultPockets()
      },
      aims: solveAims(sim),
      currentShot: evaluateCurrentShot(sim, state)
    };
  }

  function simulate(state, direction, power) {
    var sim = buildSimState(state);
    if (!sim.ok) return { ok: false, error: sim.error };
    var dir = normalizeDir(direction || { x: 1, y: 0 });
    if (!dir) return { ok: false, error: 'اتجاه غير صالح' };
    var simAngle = normDirToSimAngle(dir);
    var res = simulateShot(sim, simAngle,
      (typeof power === 'number') ? power : sim.power, sim.spin);
    return {
      ok: true,
      cuePath: toNormPath(res.paths[0]),
      paths: res.paths.map(toNormPath),
      outcome: res.outcome.map(function (o) {
        return { pocketed: o.pocketed, pocketIndex: o.pocketIndex, pos: toNormPoint(o) };
      })
    };
  }

  return {
    VERSION: '1.0.0',
    solve: solve,
    simulate: simulate,
    DEFAULT_BALL_RADIUS: DEFAULT_BALL_RADIUS,
    defaultPockets: defaultPockets,
    MAX_CUT_ANGLE_DEG: MAX_CUT_ANGLE_DEG
  };
});
