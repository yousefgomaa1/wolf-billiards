/*
 * WOLF BILLIARDS — اختبارات محرك التصويب (engine-test.js)
 * تشغيل:  node engine-test.js
 * بدون أي مكتبات خارجية — Node فقط.
 * كل اختبار يبني "حالة طاولة" (إحداثيات موحدة x∈[0,2], y∈[0,1])
 * ويتحقق من نتائج الحلّال والمحاكاة رياضيًا.
 *
 * ملاحظة هندسية: سيناريوهات القطع اختيرت بحيث خط المماس للبيضاء
 * يبتعد عن مسار الهدف (بدون تداخل)، إلا في اختبار التداخل T9 المتعمد.
 */
'use strict';

const WolfEngine = require('./wolf-engine.js');

let passed = 0, failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('  [PASS] ' + name);
  } else {
    failed++;
    console.log('  [FAIL] ' + name + (detail !== undefined ? '  → ' + detail : ''));
  }
}

function approx(a, b, tol) {
  return Math.abs(a - b) <= tol;
}

function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

function fmt(p) { return '(' + p.x.toFixed(3) + ', ' + p.y.toFixed(3) + ')'; }

function normalize(v) {
  const l = Math.hypot(v.x, v.y) || 1;
  return { x: v.x / l, y: v.y / l };
}

function pathLength(path) {
  let s = 0;
  for (let i = 1; i < path.length; i++) s += dist(path[i], path[i - 1]);
  return s;
}

// ============================================================
console.log('\n=== T1: ضربة مستقيمة في الجيب الأوسط العلوي ===');
{
  const state = {
    cueBall: { x: 1.0, y: 0.72 },
    balls: [{ x: 1.0, y: 0.45, number: 1 }],
    power: 55
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح', r.ok === true);
  check('يوجد اقتراح تصويب واحد على الأقل', r.aims.length >= 1, 'aims=' + r.aims.length);

  const a = r.aims[0];
  check('أفضل اقتراح: الكرة 0 نحو الجيب الأوسط العلوي (index 1)',
    a.ballIndex === 0 && a.pocketIndex === 1,
    'ballIndex=' + a.ballIndex + ' pocketIndex=' + a.pocketIndex);
  check('المحاكاة أكدت إدخال الجيب (makeable)', a.makeable === true,
    'makeable=' + a.makeable);
  check('بدون سقوط البيضاء (scratch)', a.scratch === false, 'scratch=' + a.scratch);
  check('زاوية القطع شبه صفرية', a.cutAngleDeg < 1.5, 'cut=' + a.cutAngleDeg);

  const ball = state.balls[0];
  check('الشبحية أعلى الكرة (في اتجاه معاكس للجيب)', a.ghost.y > ball.y,
    'ghost=' + fmt(a.ghost));
  const expectedGhostDist = dist(a.ghost, ball);
  check('الشبحية على بعد قطرين من الكرة (2R)',
    approx(expectedGhostDist, WolfEngine.DEFAULT_BALL_RADIUS * 2, 0.002),
    'dist=' + expectedGhostDist.toFixed(4));

  const dirToGhost = {
    x: a.ghost.x - state.cueBall.x,
    y: a.ghost.y - state.cueBall.y
  };
  const dLen = Math.hypot(dirToGhost.x, dirToGhost.y);
  const dot = (dirToGhost.x / dLen) * a.aimDirection.x + (dirToGhost.y / dLen) * a.aimDirection.y;
  check('اتجاه التصويب يشير من البيضاء إلى الشبحية', dot > 0.995, 'dot=' + dot.toFixed(4));

  check('مسار الهدف ينتهي داخل الجيب', approx(a.objectPath[a.objectPath.length - 1].y, 0, 0.08),
    'endY=' + a.objectPath[a.objectPath.length - 1].y.toFixed(3));
}

// ============================================================
console.log('\n=== T2: ضربة قطع (Cut) نحو الجيب العلوي الأيمن ===');
{
  // هندسة بدون تداخل: خط المماس للبيضاء يتجه لأسفل-يمين بينما الهدف يتجه لأعلى-يمين
  const state = {
    cueBall: { x: 0.5, y: 0.5 },
    balls: [{ x: 1.2, y: 0.5, number: 3 }],
    power: 60
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح', r.ok === true);

  const aim = r.aims.find(a => a.pocketIndex === 2); // الجيب (2,0)
  check('يوجد اقتراح نحو الجيب العلوي الأيمن (2,0)', !!aim,
    'aims=' + JSON.stringify(r.aims.map(a => [a.ballIndex, a.pocketIndex, a.makeable])));
  if (aim) {
    check('المحاكاة أكدت إدخال الجيب', aim.makeable === true, 'makeable=' + aim.makeable);
    check('بدون سقوط البيضاء', aim.scratch === false, 'scratch=' + aim.scratch);

    // القيم المحسوبة يدويًا: زاوية القطع ≈ 34.5° والشبحية ≈ (1.154, 0.529)
    check('زاوية القطع ≈ 34.5° (±3°)', approx(aim.cutAngleDeg, 34.5, 3),
      'cut=' + aim.cutAngleDeg);
    check('موقع الشبحية مطابق للحساب اليدوي',
      approx(aim.ghost.x, 1.1536, 0.01) && approx(aim.ghost.y, 0.529, 0.01),
      'ghost=' + fmt(aim.ghost));

    const end = aim.objectPath[aim.objectPath.length - 1];
    check('مسار الهدف وصل للجيب (2,0)', approx(end.x, 2, 0.09) && approx(end.y, 0, 0.09),
      'end=' + fmt(end));
  }
}

// ============================================================
console.log('\n=== T3: كرة معترضة تسدّ الطريق ===');
{
  const state = {
    cueBall: { x: 0.4, y: 0.5 },
    balls: [
      { x: 1.2, y: 0.5, number: 3 },   // الكرة الهدف الأصلية
      { x: 0.8, y: 0.49, number: 5 }   // كرة معترضة على خط التصويب
    ],
    power: 60
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح', r.ok === true);

  const blocked = r.aims.some(a => a.ballIndex === 0 && a.pocketIndex === 5);
  check('لم يُقترح الطريق المسدود (كرة 0 → جيب 5)', blocked === false,
    JSON.stringify(r.aims.map(a => [a.ballIndex, a.pocketIndex, a.makeable])));

  const blocked2 = r.aims.some(a => a.ballIndex === 0 && a.pocketIndex === 2);
  check('لم يُقترح الطريق المسدود الثاني (كرة 0 → جيب 2)', blocked2 === false,
    JSON.stringify(r.aims.map(a => [a.ballIndex, a.pocketIndex, a.makeable])));

  const alt = r.aims.find(a => a.ballIndex === 1 && a.pocketIndex === 5);
  check('وجد بديلًا: الكرة المعترضة نفسها نحو الجيب (2,1)', !!alt,
    JSON.stringify(r.aims.map(a => [a.ballIndex, a.pocketIndex, a.makeable])));
  if (alt) {
    check('البديل مؤكد بالمحاكاة', alt.makeable === true, 'makeable=' + alt.makeable);
    check('زاوية قطع البديل ≈ 28° (±5°)', approx(alt.cutAngleDeg, 28, 5), 'cut=' + alt.cutAngleDeg);
  }
}

// ============================================================
console.log('\n=== T4: تقييم الضربة الحالية (اتجاه العصا) ===');
{
  const state = {
    cueBall: { x: 1.0, y: 0.72 },
    balls: [{ x: 1.0, y: 0.45, number: 1 }],
    aimDirection: { x: 0, y: -1 },   // تصويب مستقيم نحو الأعلى
    power: 55
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح', r.ok === true);
  check('يوجد تقييم للضربة الحالية', !!r.currentShot);

  const cs = r.currentShot;
  check('أول اصطدام: الكرة 0', cs.firstHit && cs.firstHit.ballIndex === 0,
    JSON.stringify(cs.firstHit));
  check('نقطة التلامس = الشبحية (1.0, ~0.505)',
    approx(cs.firstHit.contact.x, 1.0, 0.005) && approx(cs.firstHit.contact.y, 0.5047, 0.008),
    'contact=' + fmt(cs.firstHit.contact));
  check('اتجاه انطلاق الهدف نحو الأعلى (0,-1)',
    cs.objectDirection && approx(cs.objectDirection.x, 0, 0.02) && approx(cs.objectDirection.y, -1, 0.02),
    cs.objectDirection ? fmt(cs.objectDirection) : 'null');
  check('المحاكاة: الهدف دخل الجيب', cs.targetPocketed === true, 'targetPocketed=' + cs.targetPocketed);
  check('المحاكاة: البيضاء لم تسقط', cs.cuePocketed === false, 'cuePocketed=' + cs.cuePocketed);
}

// ============================================================
console.log('\n=== T5: خط المماس بعد التصادم (تشتت البيضاء) ===');
{
  // نفس هندسة T2: البيضاء تنطلق بعد التلامس على خط المماس لأسفل-يمين
  const state = {
    cueBall: { x: 0.5, y: 0.5 },
    balls: [{ x: 1.2, y: 0.5, number: 3 }],
    power: 60
  };
  const r = WolfEngine.solve(state);
  const aim = r.aims.find(a => a.pocketIndex === 2);
  check('يوجد اقتراح نحو الجيب (2,0)', !!aim);
  if (aim) {
    const path = aim.cuePath;
    // أول نقطة مسجلة عند/بعد التلامس (قريبة من الشبحية)
    let k = 0;
    while (k < path.length - 1 && dist(path[k], aim.ghost) > 0.03) k++;
    check('وجد نقطة التلامس في مسار البيضاء', k < path.length - 1, 'k=' + k);
    const p1 = path[k];
    const p2 = path[Math.min(k + 3, path.length - 1)];
    const cueDir = normalize({ x: p2.x - p1.x, y: p2.y - p1.y });
    // اتجاه الهدف نحو الجيب (2,0)
    const objDir = normalize({ x: 2 - 1.2, y: 0 - 0.5 });
    const dot = cueDir.x * objDir.x + cueDir.y * objDir.y;
    check('اتجاه البيضاء بعد التلامس شبه عمودي على اتجاه الهدف (|dot| < 0.35)',
      Math.abs(dot) < 0.35, 'dot=' + dot.toFixed(3));
  }
}

// ============================================================
console.log('\n=== T6: ارتداد من الحافة (بدون كرات) ===');
{
  const state = {
    cueBall: { x: 0.5, y: 0.5 },
    balls: [],
    aimDirection: { x: 1, y: 0.2 },
    power: 70
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح بدون كرات هدف', r.ok === true);
  check('لا اقتراحات بدون كرات', r.aims.length === 0, 'aims=' + r.aims.length);

  const sim = WolfEngine.simulate({ cueBall: { x: 0.5, y: 0.5 }, balls: [] }, { x: 1, y: 0.2 }, 70);
  check('المحاكاة المباشرة نجحت', sim.ok === true);
  const path = sim.cuePath;
  const maxX = Math.max.apply(null, path.map(p => p.x));
  const last = path[path.length - 1];
  check('البيضاء اقتربت من الحافة اليمنى ثم ارتدت',
    maxX > 1.85 && last.x < maxX - 0.05,
    'maxX=' + maxX.toFixed(3) + ' lastX=' + last.x.toFixed(3));
  check('كل النقاط أرقام سليمة (لا NaN)',
    path.every(p => isFinite(p.x) && isFinite(p.y)));
  check('البيضاء لم تدخل جيبًا', sim.outcome[0].pocketed === false,
    'pocketed=' + sim.outcome[0].pocketed);
}

// ============================================================
console.log('\n=== T7: التحقق من صحة المدخلات ===');
{
  const r1 = WolfEngine.solve({ balls: [{ x: 1, y: 0.5 }] });
  check('بدون كرة بيضاء → فشل موصوف', r1.ok === false && typeof r1.error === 'string');

  const r2 = WolfEngine.solve({ cueBall: { x: 1, y: 0.5 }, balls: [] });
  check('كرات فارغة → نجاح مع aims فاضية', r2.ok === true && r2.aims.length === 0);

  const r3 = WolfEngine.solve(null);
  check('state = null → فشل موصوف', r3.ok === false);
}

// ============================================================
console.log('\n=== T8: تأثير القوة على المسافة الكلية (تكافؤ الاحتكاك) ===');
{
  const base = { cueBall: { x: 0.3, y: 0.5 }, balls: [] };
  const s25 = WolfEngine.simulate(base, { x: 1, y: 0 }, 25);
  const s90 = WolfEngine.simulate(base, { x: 1, y: 0 }, 90);
  const d25 = pathLength(s25.cuePath);
  const d90 = pathLength(s90.cuePath);
  check('قوة 90% تقطع مسافة كلية أطول من قوة 25%', d90 > d25 * 1.5,
    'd25=' + d25.toFixed(3) + ' d90=' + d90.toFixed(3));
  check('المسافة الكلية معقولة (لا انفجار طاقة)', d90 < 6, 'd90=' + d90.toFixed(3));
}

// ============================================================
console.log('\n=== T9: دقة التلامس (CCD) — التصحيح الرقمي للتلامس ===');
{
  // قبل CCD: خطأ قفزات الخطوة كان يشوه زاوية التصادم حتى 3.9°
  // ويفوّت الجيوب البعيدة (أقرب اقتراب 25.7px > نصف الالتقاط 22px).
  // بعد CCD: زاوية انطلاق الهدف يجب أن تطابق الاتجاه الهندسي المثالي.
  const state = {
    cueBall: { x: 0.5, y: 0.5 },
    balls: [{ x: 1.2, y: 0.5, number: 3 }],
    power: 60
  };
  const r = WolfEngine.solve(state);
  check('الحل نجح', r.ok === true);

  [2, 5].forEach(pocketIndex => {
    const pocketPos = pocketIndex === 2 ? { x: 2, y: 0 } : { x: 2, y: 1 };
    const aim = r.aims.find(a => a.pocketIndex === pocketIndex);
    check('يوجد اقتراح نحو الجيب ' + JSON.stringify(pocketPos), !!aim,
      'aims=' + JSON.stringify(r.aims.map(a => [a.pocketIndex, a.makeable])));
    if (!aim) return;
    check('الجيب ' + JSON.stringify(pocketPos) + ' مضمون بالمحاكاة', aim.makeable === true,
      'makeable=' + aim.makeable);

    // اتجاه الانطلاق الفعلي من أول مقطع متحرك في مسار الهدف
    const bp = aim.objectPath;
    let m = 1;
    while (m < bp.length && dist(bp[m], bp[m - 1]) < 1e-9) m++;
    const actualDir = normalize({ x: bp[m].x - bp[m - 1].x, y: bp[m].y - bp[m - 1].y });
    const idealDir = normalize({ x: pocketPos.x - 1.2, y: pocketPos.y - 0.5 });
    const dot = actualDir.x * idealDir.x + actualDir.y * idealDir.y;
    const angleErr = Math.acos(Math.max(-1, Math.min(1, dot))) * 180 / Math.PI;
    check('زاوية الانطلاق الفعلية مطابقة للمثالية (< 1.5°) للجيب ' + JSON.stringify(pocketPos),
      angleErr < 1.5, 'angleErr=' + angleErr.toFixed(2) + '°');
  });
}

// ============================================================
console.log('\n============================================');
console.log('النتيجة: ' + passed + ' ناجح / ' + failed + ' فاشل');
console.log('============================================');
process.exit(failed > 0 ? 1 : 0);
