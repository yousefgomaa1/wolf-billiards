'use strict';
// اختبار أداء: طاولة كاملة (بيضاء + 15 كرة مثلث) + طاولة متفرقة واقعية
const WolfEngine = require('./wolf-engine.js');

function bench(name, state) {
  const t0 = process.hrtime.bigint();
  const r = WolfEngine.solve(state);
  const t1 = process.hrtime.bigint();
  const ms = Number(t1 - t0) / 1e6;
  console.log(name + ':');
  console.log('  ok=' + r.ok + ' · aims=' + r.aims.length +
    ' · makeable=' + r.aims.filter(a => a.makeable).length +
    ' · زمن الحل: ' + ms.toFixed(0) + 'ms');
  r.aims.slice(0, 3).forEach(a => {
    console.log('   → كرة ' + a.ballIndex + ' (' + a.ballPos.x.toFixed(2) + ',' + a.ballPos.y.toFixed(2) +
      ') → جيب ' + a.pocketIndex + ' · قطع ' + a.cutAngleDeg + '° · مضمون=' + a.makeable +
      ' · جودة=' + a.quality);
  });
  return ms;
}

// 1) ترتيب كسر كامل (مثلث 15 كرة)
const rack = { cueBall: { x: 0.5, y: 0.5 }, balls: [], power: 60 };
const sx = 1.45, sy = 0.5, r = WolfEngine.DEFAULT_BALL_RADIUS * 2;
let n = 1;
for (let row = 0; row < 5; row++) {
  for (let col = 0; col <= row; col++) {
    rack.balls.push({
      x: sx + row * r * Math.cos(Math.PI / 6),
      y: sy + (col - row / 2) * r,
      number: n++
    });
  }
}
const m1 = bench('طاولة كاملة (ترتيب كسر - 15 كرة)', rack);

// 2) طاولة متفرقة واقعية (منتصف اللعب)
const mid = {
  cueBall: { x: 0.6, y: 0.35 },
  balls: [
    { x: 0.9, y: 0.62, number: 1 },
    { x: 1.3, y: 0.4, number: 5 },
    { x: 1.55, y: 0.75, number: 8 },
    { x: 0.5, y: 0.8, number: 12 },
    { x: 1.8, y: 0.3, number: 3 }
  ],
  power: 60
};
const m2 = bench('طاولة متفرقة (5 كرات)', mid);

// 3) تقييم اتجاه حالي مع طاولة كاملة
const t0 = process.hrtime.bigint();
const r3 = WolfEngine.solve({ ...rack, aimDirection: { x: 1, y: 0.1 } });
const t1 = process.hrtime.bigint();
console.log('تقييم اتجاه حالي مع الطاولة الكاملة: زمن=' + (Number(t1 - t0) / 1e6).toFixed(0) + 'ms' +
  ' · أول اصطدام=' + (r3.currentShot.firstHit ? 'كرة ' + r3.currentShot.firstHit.ballIndex : 'لا شيء'));

console.log('\nالخلاصة: أبطأ حالة ' + Math.max(m1, m2).toFixed(0) + 'ms — مناسب للتحديث اللحظي بمعدل 1-2 مرة/ثانية.');
