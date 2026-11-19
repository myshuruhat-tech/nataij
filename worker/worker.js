// Cloudflare Worker: وسيط بين تطبيق نتايج و ESPN (API غير رسمي، بدون مفتاح وبدون حد طلبات يومي ثابت)
//
// الطلب:  GET /fixtures?date=YYYY-MM-DD&tz=Africa/Mogadishu
// الرد:   { matches: [ {lid, ts, state, short, elapsed, home, away, hs, as, hl, al} ] }   (نفس الشكل القديم، فالتطبيق ما يتغيّر)

// slug عند ESPN -> رقم الدوري اللي يعرفه التطبيق
const LEAGUES = {
  "ksa.1": 307,          // السعودي
  "uefa.champions": 2,   // أبطال أوروبا
  "eng.1": 39,           // إنجليزي
  "esp.1": 140,          // إسباني
  "ita.1": 135,          // إيطالي
  "ger.1": 78,           // ألماني
  "fra.1": 61,           // فرنسي
  "fifa.world": 100,          // كأس العالم
  "fifa.worldq.uefa": 101,     // تصفيات كأس العالم - أوروبا
  "conmebol.america": 102,     // كوبا أمريكا
  "fifa.friendly": 103,        // ودية دولية
  "uefa.nations": 104,         // دوري الأمم الأوروبي
  "caf.nations": 105,          // كأس الأمم الأفريقية
  "afc.asian.cup": 106,        // كأس آسيا
  "usa.1": 107,                // الدوري الأمريكي MLS
  "por.1": 108,                // الدوري البرتغالي
  "afc.champions": 109,        // دوري أبطال آسيا
  "mex.1": 110,                // الدوري المكسيكي
  "col.1": 111,                // الدوري الكولمبي
  "eng.2": 112,                // الدرجة الثانية الإنجليزي (تشامبيونشيب)
  "esp.2": 113,                // الدرجة الثانية الإسباني
  "fra.2": 114,                // الدرجة الثانية الفرنسي
  "eng.trophy": 115,           // EFL Trophy الإنجليزي
  "uru.1": 116,                // الدوري الأوروغواياني
  "uefa.wchampions": 117,      // أبطال أوروبا للسيدات
  "col.copa": 118,             // كأس كولومبيا
  "slv.1": 119,                // دوري السلفادور
  "afc.saff.championship": 120, // بطولة SAFF
  "mex.w.1": 121,              // الدوري المكسيكي للسيدات
  "eng.w.league_cup": 122,     // كأس الرابطة الإنجليزي للسيدات
};
// ملاحظة: بما إن العدد صار 30 دوري وبدون ترقية خطة Cloudflare (حد 50 طلب فرعي)،
// بعض الدوريات ممكن أحيانًا ما ترجع نتيجة إذا الطلبات تجاوزت الحد — هذا قرار مقصود، مو خطأ.

const ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer";
const LEAGUE_SLUG_BY_LID = Object.fromEntries(Object.entries(LEAGUES).map(([slug, lid]) => [lid, slug]));

// حالة ESPN -> الكود القصير اللي يفهمه التطبيق
const STATUS = {
  STATUS_SCHEDULED: "NS",
  STATUS_IN_PROGRESS: "LIVE",
  STATUS_FIRST_HALF: "1H",
  STATUS_SECOND_HALF: "2H",
  STATUS_HALFTIME: "HT",
  STATUS_END_OF_REGULATION: "BT",
  STATUS_OVERTIME: "ET",
  STATUS_FIRST_HALF_EXTRA_TIME: "ET",
  STATUS_SECOND_HALF_EXTRA_TIME: "ET",
  STATUS_END_OF_EXTRA_TIME: "BT",
  STATUS_EXTRA_TIME_HALFTIME: "BT",
  STATUS_SHOOTOUT: "P",
  STATUS_FULL_TIME: "FT",
  STATUS_FINAL: "FT",
  STATUS_FINAL_AET: "AET",
  STATUS_FINAL_PEN: "PEN",
  STATUS_POSTPONED: "PST",
  STATUS_CANCELED: "CANC",
  STATUS_CANCELLED: "CANC",
  STATUS_ABANDONED: "ABD",
  STATUS_SUSPENDED: "SUSP",
  STATUS_DELAYED: "INT",
  STATUS_RAIN_DELAY: "INT",
  STATUS_INTERRUPTED: "INT",
  STATUS_FORFEIT: "AWD",
};

const ttl = (env, name, def) => Number(env[name]) || def;

const json = (obj, status = 200, extra = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extra },
  });

const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, "");

function normalize(ev, lid) {
  const comp = ev.competitions?.[0] || {};
  const type = ev.status?.type || {};
  const short = STATUS[type.name] || (type.state === "in" ? "LIVE" : type.state === "post" ? "FT" : "NS");
  const state = type.state === "in" ? "in" : type.state === "post" ? "post" : "pre";
  const home = (comp.competitors || []).find((c) => c.homeAway === "home") || {};
  const away = (comp.competitors || []).find((c) => c.homeAway === "away") || {};
  const el = parseInt(String(ev.status?.displayClock || ""), 10);
  const hasScore = state !== "pre";
  const num = (v) => (hasScore && v != null && v !== "" ? Number(v) : null);
  return {
    id: ev.id || null,
    slug: LEAGUE_SLUG_BY_LID[lid] || null,
    lid,
    ts: Date.parse(ev.date) || 0,
    state,
    short,
    elapsed: state === "in" && Number.isFinite(el) ? el : null,
    home: home.team?.displayName || home.team?.name || "—",
    away: away.team?.displayName || away.team?.name || "—",
    hs: num(home.score),
    as: num(away.score),
    hl: home.team?.logo || null,
    al: away.team?.logo || null,
  };
}

// ESPN ما عاد يقبل رنج تاريخين (dates=من-الى) — يرجّع 400. نطلب كل يوم لحاله ونجمعهم.
async function fetchLeagueDay(slug, day) {
  const r = await fetch(`${ESPN}/${slug}/scoreboard?dates=${day}&limit=300`, {
    headers: {
      "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
      accept: "application/json",
      referer: "https://www.espn.com/",
      origin: "https://www.espn.com",
    },
  });
  if (!r.ok) {
    const body = await r.text().catch(() => "");
    console.error(`fetchLeagueDay ${slug} ${day} failed: http ${r.status} ${body.slice(0, 200)}`);
    throw new Error(`http ${r.status}`);
  }
  const data = await r.json();
  return (data.events || []).map((ev) => normalize(ev, LEAGUES[slug]));
}

// ───────── تخزين مسبق في KV (fx:slug:day): يملأه الكرون بالخلفية، وطلبات /fixtures تقرأ منه بدون أي اتصال بـ ESPN وقت الطلب ─────────
const FX_TTL = 60 * 60 * 24 * 7; // 7 أيام (الكرون يكتب فقط لما البيانات تتغير، فما نحتاج تجديد دوري)
const fxKey = (slug, day) => `fx:${slug}:${day}`;

async function fetchLeagueDayCached(env, slug, day, budget) {
  if (env.LISTS) {
    const cached = await env.LISTS.get(fxKey(slug, day));
    if (cached) {
      try { return JSON.parse(cached); } catch (e) {}
    }
  }
  // ما لقيناها مخزّنة — نجيبها حي كحل احتياطي، بميزانية 40 طلب لكل طلب عشان ما نتجاوز حد الـ 50
  if (budget) { if (budget.n <= 0) throw new Error("budget"); budget.n--; }
  const events = await fetchLeagueDay(slug, day);
  if (env.LISTS) {
    env.LISTS.put(fxKey(slug, day), JSON.stringify(events), { expirationTtl: FX_TTL }).catch(() => {});
  }
  return events;
}

async function fetchLeague(env, slug, days, budget) {
  const results = await Promise.allSettled(days.map((d) => fetchLeagueDayCached(env, slug, d, budget)));
  const ok = results.filter((r) => r.status === "fulfilled");
  if (!ok.length) throw new Error("all_days_failed");
  return ok.flatMap((r) => r.value);
}

// نافذة الأيام (UTC) اللي نحتفظ لها بنسخة محدّثة — تغطي يوم قبل وبعد بالراحة لأي منطقة زمنية
function precacheDays() {
  const now = new Date();
  const base = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const out = [];
  for (let i = -2; i <= 2; i++) out.push(ymd(new Date(base.getTime() + i * 86400000)));
  return out;
}

// يشتغل كل دقيقتين (حسب الـ Cron Trigger اللي تضيفه من لوحة Cloudflare).
// كل مرة يعبي دفعة صغيرة بس (تحت حد الـ 50 طلب فرعي) ويكمل من وين وقف آخر مرة.
async function runPrecache(env) {
  if (!env.LISTS) return;
  // توفير كتابات KV (الحد المجاني 1000/يوم): ما نشتغل إلا مرة كل 5 تشغيلات (~10 دقايق لو الكرون كل دقيقتين)
  if (Math.floor(Date.now() / 120000) % 5 !== 0) return;
  const slugs = Object.keys(LEAGUES);
  const days = precacheDays();
  const today = days[2];
  // كل مرة: كل دوريات اليوم (30 طلب، عشان المباشر يتحدّث كل دقيقتين) + 15 من باقي الأيام بالتناوب = 45 (تحت حد 50)
  const others = [];
  for (const d of days) if (d !== today) for (const s of slugs) others.push([s, d]);
  const jobs = slugs.map((s) => [s, today]);
  const PER = 15;
  const start = (Math.floor(Date.now() / 120000) * PER) % others.length;
  for (let n = 0; n < PER; n++) jobs.push(others[(start + n) % others.length]);

  await Promise.allSettled(
    jobs.map(async ([slug, day]) => {
      try {
        const val = JSON.stringify(await fetchLeagueDay(slug, day));
        const key = fxKey(slug, day);
        // نكتب فقط إذا تغيّرت البيانات (حد الكتابة اليومي في KV المجاني 1000)
        if ((await env.LISTS.get(key)) !== val) await env.LISTS.put(key, val, { expirationTtl: FX_TTL });
      } catch (e) {
        console.error(`precache ${slug} ${day} failed: ${e.message}`);
      }
    })
  );
}

// ───────── مزامنة قوائم الحجب بين تطبيق الأم وتطبيق Tozy ─────────
// GET  /blocklist?pair=CODE            -> يرجّع القوائم (لأي أحد معه الكود)
// POST /blocklist?pair=CODE + x-admin-key -> يحدّث القوائم (تطبيق الأم فقط)
const PAIR_RE = /^[A-Za-z0-9_-]{8,48}$/;
const cleanList = (a) =>
  Array.isArray(a) ? a.filter((x) => typeof x === "string").map((x) => x.trim().slice(0, 80)).filter(Boolean).slice(0, 500) : [];

async function handleBlocklist(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  // لكل جهاز قوائمه المنفصلة. الجهاز اللي ما انحفظ له شي يرجع للقوائم العامة القديمة
  const dev = url.searchParams.get("device") || "";
  if (dev && !/^[A-Za-z0-9_-]{6,64}$/.test(dev)) return json({ error: "bad_device" }, 400);
  const globalKey = `pair:${pair}`;
  const key = dev ? `pair:${pair}:dev:${dev}` : globalKey;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    let raw = await env.LISTS.get(key);
    if (!raw && dev) raw = await env.LISTS.get(globalKey);
    if (!raw) return json({ empty: true }, 200, noStore);
    return new Response(raw, { headers: { "content-type": "application/json; charset=utf-8", ...noStore } });
  }
  if (request.method === "POST") {
    if (!env.ADMIN_KEY || request.headers.get("x-admin-key") !== env.ADMIN_KEY) return json({ error: "unauthorized" }, 401);
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const data = {
      handles: cleanList(body.handles),
      names: cleanList(body.names),
      exact: cleanList(body.exact),
      blocking: body.blocking !== false,
      // حزمة التطبيقات يحجب عليها بالكامل (مثل كيك)، وحزمة التطبيقات يُفحص داخلها نص القوائم أعلاه
      fullBlockApps: cleanList(body.fullBlockApps).map((s) => s.toLowerCase()),
      scanApps: cleanList(body.scanApps).map((s) => s.toLowerCase()),
      searchApps: cleanList(body.searchApps).map((s) => s.toLowerCase()),
      // تطبيقات تُسجَّل فقط (كل ما يشاهده/يبحث عنه يطلع بالنشاط) بدون أي حجب
      logApps: cleanList(body.logApps).map((s) => s.toLowerCase()),
      // رسالة التحذير اللي تطلع على جوال الولد (Toast) لما يتحجب فيديو أو تطبيق
      warningMessage: typeof body.warningMessage === "string" ? body.warningMessage.trim().slice(0, 200) : "",
      v: Date.now(),
    };
    if (typeof body.icon === "boolean") data.icon = body.icon; // إظهار/إخفاء أيقونة Tozy
    await env.LISTS.put(key, JSON.stringify(data));
    return json({ ok: true, v: data.v }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

// ───────── تقرير استخدام التطبيقات: Tozy يرفعه، تطبيق الأم يقرأه ─────────
// GET  /usage?pair=CODE          -> يرجّع آخر 14 يوم المخزّنة
// POST /usage?pair=CODE + body: { date: "YYYY-MM-DD", apps: [{pkg, label, minutes}] }
// المصادقة هنا بكود الربط فقط (نفس مستوى ثقة /blocklist للقراءة)، لأن Tozy ما معه مفتاح الأدمن
const MAX_USAGE_DAYS = 14;

function cleanApps(a) {
  if (!Array.isArray(a)) return [];
  return a
    .filter((x) => x && typeof x.pkg === "string")
    .map((x) => ({
      pkg: x.pkg.trim().slice(0, 120),
      label: typeof x.label === "string" && x.label.trim() ? x.label.trim().slice(0, 80) : x.pkg,
      minutes: Number.isFinite(x.minutes) ? Math.max(0, Math.min(1440, Math.round(x.minutes))) : 0,
    }))
    .slice(0, 200);
}

async function handleUsage(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const udev = url.searchParams.get("device") || "";
  if (udev && !/^[A-Za-z0-9_-]{6,64}$/.test(udev)) return json({ error: "bad_device" }, 400);
  const key = udev ? `usage:${pair}:dev:${udev}` : `usage:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    return new Response(raw || "{}", { headers: { "content-type": "application/json; charset=utf-8", ...noStore } });
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const date = typeof body.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(body.date) ? body.date : null;
    if (!date) return json({ error: "bad_date" }, 400);
    const raw = await env.LISTS.get(key);
    let store = {};
    if (raw) { try { store = JSON.parse(raw); } catch (e) { store = {}; } }
    store[date] = { apps: cleanApps(body.apps), ts: Date.now() };
    const days = Object.keys(store).sort();
    while (days.length > MAX_USAGE_DAYS) delete store[days.shift()];
    await env.LISTS.put(key, JSON.stringify(store));
    return json({ ok: true }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

// ───────── سجل البحث: Tozy يرفع كلمة بحث واحدة في كل مرة (من صندوق بحث محدد بالاسم فقط)، تطبيق الأم يقرأه ─────────
// GET  /searchlog?pair=CODE          -> يرجّع آخر 300 عملية بحث
// POST /searchlog?pair=CODE + body: { app: "com.google.android.youtube", label: "يوتيوب", q: "..." }
// نفس مستوى ثقة /usage (كود الربط فقط، بدون مفتاح أدمن) لأن Tozy ما معه المفتاح
const MAX_SEARCH_ENTRIES = 300;

function cleanQuery(q) {
  if (typeof q !== "string") return "";
  return q.trim().replace(/\s+/g, " ").slice(0, 200);
}

async function handleSearchLog(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const key = `search:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    return new Response(raw || '{"entries":[]}', { headers: { "content-type": "application/json; charset=utf-8", ...noStore } });
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const q = cleanQuery(body.q);
    const app = typeof body.app === "string" ? body.app.trim().slice(0, 120) : "";
    const label = typeof body.label === "string" && body.label.trim() ? body.label.trim().slice(0, 80) : app;
    if (!q || !app) return json({ error: "bad_entry" }, 400); // ما نخزّن إدخال فاضي
    const raw = await env.LISTS.get(key);
    let store = { entries: [] };
    if (raw) {
      try { store = JSON.parse(raw); if (!Array.isArray(store.entries)) store.entries = []; } catch (e) { store = { entries: [] }; }
    }
    // تفادي تكرار نفس البحث لو انرفع مرتين قريب من بعض (نفس التطبيق ونفس الكلمة خلال آخر دقيقتين)
    const now = Date.now();
    const dup = store.entries[0];
    if (dup && dup.app === app && dup.q === q && now - Date.parse(dup.ts) < 120000) {
      return json({ ok: true, deduped: true }, 200, noStore);
    }
    store.entries.unshift({ ts: new Date().toISOString(), app, label, q });
    store.entries = store.entries.slice(0, MAX_SEARCH_ENTRIES);
    await env.LISTS.put(key, JSON.stringify(store));
    return json({ ok: true }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

// ───────── سجل النشاط (مشاهدة/إخفاء/حجب): Tozy يرفع كل حدث، تطبيق الأم يقرأه ─────────
// GET  /activity?pair=CODE          -> يرجّع آخر 500 حدث
// POST /activity?pair=CODE + body: { type, kind, channel, label }
const MAX_ACTIVITY_ENTRIES = 500;

async function handleActivity(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const noStore = { "cache-control": "no-store" };
  const dayKey = (d) => `actday:${pair}:${d}`;
  const readEntries = async (k) => {
    const r = await env.LISTS.get(k);
    if (!r) return [];
    try { const o = JSON.parse(r); return Array.isArray(o.entries) ? o.entries : []; } catch (e) { return []; }
  };
  if (request.method === "GET") {
    const q = url.searchParams;
    if (q.get("days")) {
      const prefix = `actday:${pair}:`;
      const days = [];
      let cursor;
      do {
        const r = await env.LISTS.list({ prefix, cursor });
        r.keys.forEach((k) => days.push(k.name.slice(prefix.length)));
        cursor = r.list_complete ? undefined : r.cursor;
      } while (cursor);
      days.sort().reverse();
      return json({ days }, 200, noStore);
    }
    const d = q.get("day");
    if (d) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return json({ error: "bad_day" }, 400);
      return json({ entries: await readEntries(dayKey(d)) }, 200, noStore);
    }
    const today = new Date().toISOString().slice(0, 10);
    const yday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
    const [a, b, legacy] = await Promise.all([readEntries(dayKey(today)), readEntries(dayKey(yday)), readEntries(`activity:${pair}`)]);
    return json({ entries: [...a, ...b, ...legacy].slice(0, MAX_ACTIVITY_ENTRIES) }, 200, noStore);
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const clean = (s, n) => (typeof s === "string" ? s.trim().slice(0, n) : "");
    const type = clean(body.type, 20);
    const kind = clean(body.kind, 10);
    const channel = clean(body.channel, 120);
    const label = clean(body.label, 200);
    if (!type || !label) return json({ error: "bad_entry" }, 400);
    const now = new Date();
    const key = dayKey(now.toISOString().slice(0, 10));
    const entries = await readEntries(key);
    entries.unshift({ ts: now.toISOString(), type, kind, channel, label, device: clean(body.device, 64), deviceName: clean(body.deviceName, 40), where: clean(body.where, 60), matched: clean(body.matched, 80), thumb: /^[a-f0-9]{8,16}$/.test(body.thumb || "") ? body.thumb : "" });
    await env.LISTS.put(key, JSON.stringify({ entries: entries.slice(0, 20000) })); // بدون انتهاء: يُحفظ للأبد
    return json({ ok: true }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

// ───────── صور مصغّرة للمقاطع المُشاهَدة (تنحذف بعد 30 يوم) ─────────
// POST /thumb?pair=CODE&device=ID&id=HEX  body: صورة JPEG   |   GET /thumb?pair=CODE&id=HEX
async function handleThumb(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  const id = url.searchParams.get("id") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!/^[a-f0-9]{8,16}$/.test(id)) return json({ error: "bad_id" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const key = `thumb:${pair}:${id}`;
  if (request.method === "GET") {
    const buf = await env.LISTS.get(key, "arrayBuffer");
    if (!buf) return new Response("not found", { status: 404 });
    return new Response(buf, { headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400" } });
  }
  if (request.method === "POST") {
    const buf = await request.arrayBuffer();
    if (buf.byteLength === 0 || buf.byteLength > 150000) return json({ error: "bad_size" }, 400);
    await env.LISTS.put(key, buf, { expirationTtl: 30 * 86400 });
    return json({ ok: true }, 200, { "cache-control": "no-store" });
  }
  return json({ error: "method" }, 405);
}

// ───────── أيقونات التطبيقات لكل جهاز ─────────
// POST /icons?pair=CODE&device=ID  body: { icons: { "pkg": "base64png" } }   |   GET نفس الرابط
async function handleIcons(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  const device = url.searchParams.get("device") || "";
  if (!PAIR_RE.test(pair) || !/^[\w-]{1,64}$/.test(device)) return json({ error: "bad_params" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const key = `icons:${pair}:${device}`;
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    return new Response(raw || "{}", { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const cur = JSON.parse((await env.LISTS.get(key)) || "{}");
    for (const [pkg, b64] of Object.entries(body.icons || {})) {
      if (/^[\w.]{1,120}$/.test(pkg) && typeof b64 === "string" && b64.length < 20000) cur[pkg] = b64;
    }
    const keys = Object.keys(cur);
    if (keys.length > 400) keys.slice(0, keys.length - 400).forEach((k) => delete cur[k]);
    await env.LISTS.put(key, JSON.stringify(cur));
    return json({ ok: true }, 200, { "cache-control": "no-store" });
  }
  return json({ error: "method" }, 405);
}

// ───────── تفاصيل مباراة: تشكيلة + إحصائيات بسيطة + تعليق دقيقة بدقيقة ─────────
// GET /match?id=EVENT_ID&slug=eng.1
async function handleMatch(url) {
  const id = url.searchParams.get("id") || "";
  const slug = url.searchParams.get("slug") || "";
  if (!/^\d+$/.test(id) || !LEAGUES[slug]) return json({ error: "bad_params" }, 400);

  const cache = caches.default;
  const cacheKey = new Request(`https://cache.nataij/match/${slug}/${id}`);
  const hit = await cache.match(cacheKey);
  if (hit) return hit;

  let data;
  try {
    const r = await fetch(`${ESPN}/${slug}/summary?event=${id}`, {
      headers: { "user-agent": "Mozilla/5.0", accept: "application/json" },
    });
    if (!r.ok) throw new Error(`http ${r.status}`);
    data = await r.json();
  } catch (e) {
    return json({ error: "upstream" }, 502);
  }

  // التعليق: الأحدث أول
  const commentary = (data.commentary || [])
    .map((c) => ({ t: c.time?.displayValue || "", text: c.text || "" }))
    .filter((c) => c.text)
    .slice(0, 60);

  // التشكيلة: لكل فريق، الأساسيين فقط
  const lineups = (data.rosters || []).map((t) => ({
    team: t.team?.displayName || t.team?.name || "—",
    formation: t.formation || null,
    starters: (t.roster || [])
      .filter((p) => p.starter)
      .map((p) => ({ name: p.athlete?.displayName || "—", num: p.jersey || "", pos: p.position?.abbreviation || "" })),
  }));

  // إحصائيات بسيطة من الـ boxscore
  const teamsStats = data.boxscore?.teams || [];
  const statsByName = {};
  (teamsStats[0]?.statistics || []).forEach((s) => { statsByName[s.name || s.label] = { label: s.label || s.name, home: s.displayValue }; });
  (teamsStats[1]?.statistics || []).forEach((s) => {
    const k = s.name || s.label;
    if (statsByName[k]) statsByName[k].away = s.displayValue;
    else statsByName[k] = { label: s.label || s.name, home: "-", away: s.displayValue };
  });
  const stats = Object.values(statsByName);

  const body = { commentary, lineups, stats };
  const res = json(body, 200, { "cache-control": "public, max-age=25" });
  if (commentary.length || lineups.length || stats.length) {
    cache.put(cacheKey, json(body, 200, { "cache-control": "public, max-age=25" })).catch(() => {});
  }
  return res;
}

// ───────── الأجهزة: كل جهاز Tozy يسجّل نفسه، والأم تتحكم بحجب كل جهاز لحاله ─────────
// POST /devices?pair=CODE  body:{id,name}            -> تسجيل/نبض دوري (أي جهاز، بدون مفتاح)
// POST /devices?pair=CODE  body:{id,blocking} + key   -> تغيير حالة الحجب لجهاز معيّن (الأم فقط)
// GET  /devices?pair=CODE                             -> قائمة الأجهزة
const DEV_ID_RE = /^[A-Za-z0-9_-]{6,64}$/;
async function handleDevices(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!env.LISTS) return json({ error: "no_kv" }, 500);
  const key = `devices:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    const map = raw ? JSON.parse(raw) : {};
    const devices = Object.keys(map).map((id) => ({ id, ...map[id], name: map[id].customName || map[id].name }));
    return json({ devices }, 200, noStore);
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const id = typeof body.id === "string" ? body.id.trim() : "";
    if (!DEV_ID_RE.test(id)) return json({ error: "bad_id" }, 400);
    const raw = await env.LISTS.get(key);
    const map = raw ? JSON.parse(raw) : {};
    const isAdmin = !!env.ADMIN_KEY && request.headers.get("x-admin-key") === env.ADMIN_KEY;
    let skipWrite = false;
    if (isAdmin) {
      if (!map[id]) return json({ error: "not_found" }, 404);
      if (body.remove === true) delete map[id];
      else {
        if (typeof body.blocking === "boolean") map[id].blocking = body.blocking;
        if (typeof body.logging === "boolean") map[id].logging = body.logging;
        if (typeof body.name === "string") map[id].customName = body.name.trim().slice(0, 40);
      }
    } else {
      const name = typeof body.name === "string" ? body.name.trim().slice(0, 40) : "جهاز";
      const existing = map[id];
      const apps = Array.isArray(body.apps)
        ? body.apps.slice(0, 400).filter((x) => x && typeof x.pkg === "string").map((x) => ({ pkg: x.pkg.slice(0, 120), label: String(x.label || x.pkg).slice(0, 60) }))
        : existing?.apps;
      const same = existing && existing.name === name && JSON.stringify(existing.apps || []) === JSON.stringify(apps || []) && Date.now() - (existing.lastSeen || 0) < 10 * 60 * 1000;
      if (same) skipWrite = true;
      else map[id] = { ...existing, name, apps, blocking: existing ? existing.blocking : true, lastSeen: Date.now() };
    }
    if (!skipWrite) await env.LISTS.put(key, JSON.stringify(map));
    return json({ ok: true, blocking: map[id]?.blocking !== false, logging: map[id]?.logging !== false }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

// ───────── بث الشاشة: طلب -> بث مباشر -> إطارات ─────────
// POST   /screen?pair&device        + x-admin-key  -> الأم تطلب البث (TTL دقيقتين)
// GET    /screen?pair&device                        -> الجهاز يسأل: فيه طلب؟
// DELETE /screen?pair&device                        -> الجهاز يمسح الطلب بعد القرار
// POST   /screen/live?pair&device   + x-admin-key  -> الأم تجدد "أنا شايفة" كل ~6 ثواني (TTL 15 ث)
// GET    /screen/live?pair&device                   -> الجهاز يسأل: لسا فيه مشاهد؟
// POST   /screen/frame?pair&device                  -> الجهاز يرفع آخر صورة (TTL 20 ث)
// GET    /screen/frame?pair&device  + x-admin-key  -> الأم تجيب آخر صورة
async function handleScreen(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  const device = url.searchParams.get("device") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!DEV_ID_RE.test(device)) return json({ error: "bad_id" }, 400);
  const noStore = { "cache-control": "no-store" };
  const isAdmin = !!env.ADMIN_KEY && request.headers.get("x-admin-key") === env.ADMIN_KEY;
  const sub = url.pathname.slice("/screen".length); // "", "/live", "/frame"
  // الأفضل: كل حالة البث (طلب/بث/إطار) تعيش بذاكرة Durable Object، بدون أي كتابة في KV
  if (env.SCREEN) {
    const stub = env.SCREEN.get(env.SCREEN.idFromName(`${pair}:${device}`));
    const h = new Headers();
    h.set("x-admin", isAdmin ? "1" : "0");
    const init = { method: request.method, headers: h };
    if (request.method === "POST") init.body = await request.text();
    return stub.fetch(`https://hub${sub || "/"}`, init);
  }
  if (!env.LISTS) return json({ error: "no_kv" }, 500);

  if (sub === "") {
    const key = `screen:${pair}:${device}`;
    if (request.method === "GET") return json({ requested: (await env.LISTS.get(key)) !== null }, 200, noStore);
    if (request.method === "POST") {
      if (!isAdmin) return json({ error: "unauthorized" }, 401);
      await env.LISTS.put(key, String(Date.now()), { expirationTtl: 120 });
      return json({ ok: true }, 200, noStore);
    }
    if (request.method === "DELETE") { await env.LISTS.delete(key); return json({ ok: true }, 200, noStore); }
  }

  if (sub === "/live") {
    const key = `live:${pair}:${device}`;
    // ملاحظة: أقل expirationTtl يقبله KV هو 60 ثانية، فنخزّن الوقت ونتحقق من الطزاجة (15 ث) بالكود
    if (request.method === "GET") {
      const v = await env.LISTS.get(key);
      return json({ live: v !== null && Date.now() - Number(v) < 15000 }, 200, noStore);
    }
    if (request.method === "POST") {
      if (!isAdmin) return json({ error: "unauthorized" }, 401);
      await env.LISTS.put(key, String(Date.now()), { expirationTtl: 60 });
      return json({ ok: true }, 200, noStore);
    }
  }

  if (sub === "/frame") {
    const key = `frame:${pair}:${device}`;
    if (request.method === "POST") {
      let body; try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
      const jpg = typeof body.jpg === "string" ? body.jpg : "";
      if (!jpg || jpg.length > 900000) return json({ error: "bad_frame" }, 400);
      // نخزّن "الوقت|الصورة" (base64 ما فيه |)، والحد الأدنى لـ TTL بـ KV هو 60 ثانية
      await env.LISTS.put(key, Date.now() + "|" + jpg, { expirationTtl: 60 });
      return json({ ok: true }, 200, noStore);
    }
    if (request.method === "GET") {
      if (!isAdmin) return json({ error: "unauthorized" }, 401);
      const raw = await env.LISTS.get(key);
      let jpg = null;
      if (raw) {
        const i = raw.indexOf("|");
        if (i > 0 && Date.now() - Number(raw.slice(0, i)) < 20000) jpg = raw.slice(i + 1);
      }
      return json({ jpg }, 200, noStore);
    }
  }

  return json({ error: "method" }, 405);
}


// ───────── PairHub: سجل النشاط + سجل البحث + اللقطات بتخزين SQLite داخل Durable Object ─────────
// قراءة/كتابة فورية ومتسقة (KV كان يتأخر لدقيقة وممكن يضيّع إدخالات متزامنة)، وما يستهلك حد كتابات KV اليومي.
export class PairHub {
  constructor(state, env) {
    this.sql = state.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS activity(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, ts TEXT NOT NULL, j TEXT NOT NULL)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS act_day ON activity(day)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS search(id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, j TEXT NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS thumbs(id TEXT PRIMARY KEY, ts INTEGER NOT NULL, b BLOB NOT NULL)");
    this.lastPrune = 0;
  }
  async fetch(request) {
    const url = new URL(request.url);
    const q = url.searchParams;
    const ok = (o) => json(o, 200, { "cache-control": "no-store" });
    const path = url.pathname;
    if (path === "/act") {
      if (request.method === "POST") {
        const e = JSON.parse(await request.text());
        this.sql.exec("INSERT INTO activity(day, ts, j) VALUES (?, ?, ?)", e.ts.slice(0, 10), e.ts, JSON.stringify(e));
        return ok({ ok: true });
      }
      if (q.get("days")) {
        const rows = this.sql.exec("SELECT DISTINCT day FROM activity").toArray();
        return ok({ days: rows.map((r) => r.day) });
      }
      const ds = (q.get("d") || "").split(",").filter((x) => /^\d{4}-\d{2}-\d{2}$/.test(x)).slice(0, 3);
      const out = [];
      for (const d of ds) {
        for (const r of this.sql.exec("SELECT j FROM activity WHERE day = ? ORDER BY id DESC LIMIT 5000", d).toArray()) {
          try { out.push(JSON.parse(r.j)); } catch (e) {}
        }
      }
      return ok({ entries: out });
    }
    if (path === "/sl") {
      if (request.method === "POST") {
        const e = JSON.parse(await request.text());
        const last = this.sql.exec("SELECT j FROM search ORDER BY id DESC LIMIT 1").toArray()[0];
        if (last) {
          try {
            const d = JSON.parse(last.j);
            if (d.app === e.app && d.q === e.q && Date.now() - Date.parse(d.ts) < 120000) return ok({ ok: true, deduped: true });
          } catch (x) {}
        }
        this.sql.exec("INSERT INTO search(ts, j) VALUES (?, ?)", e.ts, JSON.stringify(e));
        this.sql.exec("DELETE FROM search WHERE id <= (SELECT MAX(id) FROM search) - ?", MAX_SEARCH_ENTRIES);
        return ok({ ok: true });
      }
      const out = [];
      for (const r of this.sql.exec("SELECT j FROM search ORDER BY id DESC LIMIT ?", MAX_SEARCH_ENTRIES).toArray()) {
        try { out.push(JSON.parse(r.j)); } catch (e) {}
      }
      return ok({ entries: out });
    }
    if (path === "/th") {
      const id = q.get("id") || "";
      if (request.method === "PUT") {
        const buf = await request.arrayBuffer();
        const now = Date.now();
        this.sql.exec("INSERT OR REPLACE INTO thumbs(id, ts, b) VALUES (?, ?, ?)", id, now, buf);
        if (now - this.lastPrune > 6 * 3600 * 1000) { // تنظيف اللقطات الأقدم من 30 يوم (مرة كل 6 ساعات)
          this.lastPrune = now;
          this.sql.exec("DELETE FROM thumbs WHERE ts < ?", now - 30 * 86400 * 1000);
        }
        return ok({ ok: true });
      }
      const r = this.sql.exec("SELECT b FROM thumbs WHERE id = ?", id).toArray()[0];
      if (!r) return new Response("not found", { status: 404 });
      return new Response(r.b, { headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400" } });
    }
    return json({ error: "not_found" }, 404);
  }
}

const hubOf = (env, pair) => env.PAIRHUB.get(env.PAIRHUB.idFromName(pair));
const byTsDesc = (a, b) => (a.ts < b.ts ? 1 : a.ts > b.ts ? -1 : 0);

async function handleActivityDO(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  const noStore = { "cache-control": "no-store" };
  const hub = hubOf(env, pair);
  if (request.method === "GET") {
    const q = url.searchParams;
    const kvRead = async (k) => {
      if (!env.LISTS) return [];
      try { const r = await env.LISTS.get(k); const o = r ? JSON.parse(r) : null; return o && Array.isArray(o.entries) ? o.entries : []; } catch (e) { return []; }
    };
    if (q.get("days")) {
      const days = new Set();
      try { ((await (await hub.fetch("https://hub/act?days=1")).json()).days || []).forEach((d) => days.add(d)); } catch (e) {}
      if (env.LISTS) {
        const prefix = `actday:${pair}:`;
        let cursor;
        try { do { const r = await env.LISTS.list({ prefix, cursor }); r.keys.forEach((k) => days.add(k.name.slice(prefix.length))); cursor = r.list_complete ? undefined : r.cursor; } while (cursor); } catch (e) {}
      }
      return json({ days: [...days].sort().reverse() }, 200, noStore);
    }
    const d = q.get("day");
    if (d) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return json({ error: "bad_day" }, 400);
      const [a, b] = await Promise.all([hub.fetch(`https://hub/act?d=${d}`).then((r) => r.json()), kvRead(`actday:${pair}:${d}`)]);
      return json({ entries: [...(a.entries || []), ...b].sort(byTsDesc) }, 200, noStore);
    }
    const today = new Date().toISOString().slice(0, 10);
    const yday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
    const [a, kt, ky, legacy] = await Promise.all([
      hub.fetch(`https://hub/act?d=${today},${yday}`).then((r) => r.json()),
      kvRead(`actday:${pair}:${today}`), kvRead(`actday:${pair}:${yday}`), kvRead(`activity:${pair}`),
    ]);
    return json({ entries: [...(a.entries || []), ...kt, ...ky, ...legacy].sort(byTsDesc).slice(0, MAX_ACTIVITY_ENTRIES) }, 200, noStore);
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const clean = (s, n) => (typeof s === "string" ? s.trim().slice(0, n) : "");
    const type = clean(body.type, 20);
    const label = clean(body.label, 200);
    if (!type || !label) return json({ error: "bad_entry" }, 400);
    const entry = { ts: new Date().toISOString(), type, kind: clean(body.kind, 10), channel: clean(body.channel, 120), label, device: clean(body.device, 64), deviceName: clean(body.deviceName, 40), where: clean(body.where, 60), matched: clean(body.matched, 80), thumb: /^[a-f0-9]{8,16}$/.test(body.thumb || "") ? body.thumb : "" };
    const r = await hub.fetch("https://hub/act", { method: "POST", body: JSON.stringify(entry) });
    return json({ ok: r.ok }, r.ok ? 200 : 500, noStore);
  }
  return json({ error: "method" }, 405);
}

async function handleSearchLogDO(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  const noStore = { "cache-control": "no-store" };
  const hub = hubOf(env, pair);
  if (request.method === "GET") {
    const a = await hub.fetch("https://hub/sl").then((r) => r.json());
    let old = [];
    if (env.LISTS) { try { const raw = await env.LISTS.get(`search:${pair}`); old = raw ? (JSON.parse(raw).entries || []) : []; } catch (e) {} }
    return json({ entries: [...(a.entries || []), ...old].sort(byTsDesc).slice(0, MAX_SEARCH_ENTRIES) }, 200, noStore);
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const q = cleanQuery(body.q);
    const app = typeof body.app === "string" ? body.app.trim().slice(0, 120) : "";
    const label = typeof body.label === "string" && body.label.trim() ? body.label.trim().slice(0, 80) : app;
    if (!q || !app) return json({ error: "bad_entry" }, 400);
    const entry = { ts: new Date().toISOString(), app, label, q };
    const r = await hub.fetch("https://hub/sl", { method: "POST", body: JSON.stringify(entry) });
    return json({ ok: r.ok }, r.ok ? 200 : 500, noStore);
  }
  return json({ error: "method" }, 405);
}

async function handleThumbDO(request, env, url) {
  const pair = url.searchParams.get("pair") || "";
  const id = url.searchParams.get("id") || "";
  if (!PAIR_RE.test(pair)) return json({ error: "bad_pair" }, 400);
  if (!/^[a-f0-9]{8,16}$/.test(id)) return json({ error: "bad_id" }, 400);
  const hub = hubOf(env, pair);
  if (request.method === "GET") {
    const r = await hub.fetch(`https://hub/th?id=${id}`);
    if (r.status === 200) return r;
    if (env.LISTS) { // لقطات قديمة انحفظت بـ KV قبل النقل
      const buf = await env.LISTS.get(`thumb:${pair}:${id}`, "arrayBuffer");
      if (buf) return new Response(buf, { headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400" } });
    }
    return new Response("not found", { status: 404 });
  }
  if (request.method === "POST") {
    const buf = await request.arrayBuffer();
    if (buf.byteLength === 0 || buf.byteLength > 150000) return json({ error: "bad_size" }, 400);
    const r = await hub.fetch(`https://hub/th?id=${id}`, { method: "PUT", body: buf });
    return json({ ok: r.ok }, r.ok ? 200 : 500, { "cache-control": "no-store" });
  }
  return json({ error: "method" }, 405);
}

// ───────── Durable Object: حالة بث الشاشة بالذاكرة فقط (لا يكتب بـ KV ولا بالتخزين) ─────────
// لو انطفى الـ DO من الخمول يضيع آخر إطار/طلب، وهذا مقبول لأنها بيانات مؤقتة (ثواني).
export class ScreenHub {
  constructor(state, env) { this.state = state; this.reqTs = 0; this.liveTs = 0; this.frame = null; this.status = null; this.statusTs = 0; this.reqs = null; }
  async getReqs() { if (this.reqs === null) this.reqs = (await this.state.storage.get("reqs")) || []; return this.reqs; }
  async fetch(request) {
    const noStore = { "cache-control": "no-store" };
    const sub = new URL(request.url).pathname.replace(/\/$/, "");
    const admin = request.headers.get("x-admin") === "1";
    const m = request.method;
    const now = Date.now();
    if (sub === "") {
      if (m === "GET") return json({ requested: now - this.reqTs < 120000 }, 200, noStore);
      if (m === "POST") { if (!admin) return json({ error: "unauthorized" }, 401); this.reqTs = now; return json({ ok: true }, 200, noStore); }
      if (m === "DELETE") { this.reqTs = 0; return json({ ok: true }, 200, noStore); }
    }
    if (sub === "/live") {
      if (m === "GET") return json({ live: now - this.liveTs < 15000 }, 200, noStore);
      if (m === "POST") { if (!admin) return json({ error: "unauthorized" }, 401); this.liveTs = now; return json({ ok: true }, 200, noStore); }
    }
    // إيقاف المشاهدة فوراً: الأم تضغط إيقاف فيوقف البث عند الجهاز خلال ثواني
    if (sub === "/stop" && m === "POST") {
      if (!admin) return json({ error: "unauthorized" }, 401);
      this.liveTs = 0; this.frame = null; this.reqTs = 0;
      return json({ ok: true }, 200, noStore);
    }
    // حالة الجهاز (إمكانية الوصول/اللقطات/الإشعارات/البطارية/التطبيق الحالي): بالذاكرة بدون كتابات KV
    if (sub === "/status") {
      if (m === "POST") {
        let b; try { b = JSON.parse(await request.text()); } catch (e) { return json({ error: "bad_json" }, 400); }
        const x = b && typeof b === "object" ? b : {};
        this.status = { acc: x.acc === true, shot: x.shot === true ? true : x.shot === false ? false : null, notif: x.notif !== false, battery: x.battery === true, app: String(x.app || "").slice(0, 40) };
        this.statusTs = now;
        const r = await this.getReqs();
        const st = this.status;
        const left = r.filter((t) => !((t === "acc" && st.acc) || (t === "shot" && st.shot === true) || (t === "notif" && st.notif) || (t === "battery" && st.battery)));
        if (left.length !== r.length) { this.reqs = left; await this.state.storage.put("reqs", left); }
        return json({ requests: this.reqs }, 200, noStore);
      }
      if (m === "GET") {
        if (!admin) return json({ error: "unauthorized" }, 401);
        return json({ status: this.status, at: this.statusTs, requests: await this.getReqs() }, 200, noStore);
      }
    }
    // الأم تطلب من الجهاز تفعيل خاصية
    if (sub === "/request" && m === "POST") {
      if (!admin) return json({ error: "unauthorized" }, 401);
      let b; try { b = JSON.parse(await request.text()); } catch (e) { return json({ error: "bad_json" }, 400); }
      const t = b && b.type;
      if (!["acc", "shot", "notif", "battery"].includes(t)) return json({ error: "bad_type" }, 400);
      const r = await this.getReqs();
      if (!r.includes(t)) { r.push(t); await this.state.storage.put("reqs", r); }
      return json({ ok: true }, 200, noStore);
    }
    if (sub === "/frame") {
      if (m === "POST") {
        let body; try { body = JSON.parse(await request.text()); } catch (e) { return json({ error: "bad_json" }, 400); }
        const jpg = body && typeof body.jpg === "string" ? body.jpg : "";
        if (!jpg || jpg.length > 900000) return json({ error: "bad_frame" }, 400);
        this.frame = { ts: now, jpg };
        return json({ ok: true }, 200, noStore);
      }
      if (m === "GET") {
        if (!admin) return json({ error: "unauthorized" }, 401);
        const f = this.frame;
        return json({ jpg: f && now - f.ts < 20000 ? f.jpg : null }, 200, noStore);
      }
    }
    return json({ error: "method" }, 405);
  }
}

export default {
  // غلاف يمسك أي استثناء ويرجّعه كـ JSON بدل 500 صامت (مثلاً KV put() limit exceeded)
  async fetch(request, env, ctx) {
    try {
      return await this.handle(request, env, ctx);
    } catch (e) {
      const msg = String((e && e.message) || e);
      console.error("worker_error", request.method, new URL(request.url).pathname, msg);
      const limit = /limit exceeded/i.test(msg);
      return json({ error: limit ? "kv_limit" : "server_error", detail: msg.slice(0, 200) }, limit ? 503 : 500, { "cache-control": "no-store" });
    }
  },

  async handle(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/debug") {
      const slug = url.searchParams.get("slug") || "eng.1";
      const day = url.searchParams.get("day") || ymd(new Date());
      const out = { slug, day, kv: !!env.LISTS };
      try {
        const r = await fetch(`${ESPN}/${slug}/scoreboard?dates=${day}&limit=300`, { headers: { "user-agent": "Mozilla/5.0", accept: "application/json" } });
        out.espn_status = r.status;
        const t = await r.text();
        out.espn_len = t.length;
        try { out.espn_events = (JSON.parse(t).events || []).length; } catch (e) { out.espn_body = t.slice(0, 150); }
      } catch (e) { out.espn_error = String(e); }
      if (env.LISTS) { const c = await env.LISTS.get(fxKey(slug, day)); out.kv_len = c ? c.length : null; out.kv_head = c ? c.slice(0, 80) : null; }
      return json(out, 200, { "cache-control": "no-store" });
    }
    if (url.pathname === "/blocklist") return handleBlocklist(request, env, url);
    if (url.pathname === "/usage") return handleUsage(request, env, url);
    if (url.pathname === "/searchlog") return (env.PAIRHUB ? handleSearchLogDO : handleSearchLog)(request, env, url);
    if (url.pathname === "/activity") return (env.PAIRHUB ? handleActivityDO : handleActivity)(request, env, url);
    if (url.pathname === "/icons") return handleIcons(request, env, url);
    if (url.pathname === "/thumb") return (env.PAIRHUB ? handleThumbDO : handleThumb)(request, env, url);
    if (url.pathname === "/match") return handleMatch(url);
    if (url.pathname === "/devices") return handleDevices(request, env, url);
    if (url.pathname === "/screen" || url.pathname.startsWith("/screen/")) return handleScreen(request, env, url);
    if (request.method !== "GET") return json({ error: "method" }, 405);
    if (url.pathname !== "/fixtures") return json({ ok: true, service: "nataij" });

    const date = url.searchParams.get("date") || "";
    const tz = url.searchParams.get("tz") || "UTC";
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "bad_date" }, 400);
    if (!/^[A-Za-z_]+(\/[A-Za-z_+\-0-9]+){0,2}$/.test(tz)) return json({ error: "bad_tz" }, 400);

    let fmt;
    try {
      fmt = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" });
    } catch (e) {
      return json({ error: "bad_tz" }, 400);
    }

    const tzOffsetMinutes = (d, timeZone) => {
      const p = new Intl.DateTimeFormat("en-US", {
        timeZone, hour12: false, year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", second: "2-digit",
      }).formatToParts(d).reduce((acc, x) => { acc[x.type] = x.value; return acc; }, {});
      const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour === 24 ? 0 : +p.hour, +p.minute, +p.second);
      return (asUTC - d.getTime()) / 60000;
    };

    // الكاش حسب (التاريخ + المنطقة) فقط، فكل المستخدمين يشاركون نفس الطلب
    const cache = caches.default;
    const freshKey = new Request(`https://cache.nataij/fresh/${date}/${tz}`);
    const staleKey = new Request(`https://cache.nataij/stale/${date}/${tz}`);

    const hit = await cache.match(freshKey);
    if (hit) return hit;

    // ESPN يقسّم الأيام بتوقيت UTC، فاليوم المحلي يلامس يوم UTC ثاني حسب فرق التوقيت.
    // نحسب الاتجاه الصح (قبل أو بعد) بدل ما نجيب 3 أيام ثابتة لكل دوري — يقلل عدد الطلبات ويخليها تحت حد Cloudflare.
    const base = new Date(`${date}T12:00:00Z`);
    const offsetMin = tzOffsetMinutes(base, tz);
    const days =
      offsetMin >= 0
        ? [ymd(new Date(base.getTime() - 86400000)), ymd(base)] // المنطقة شرق UTC (أو UTC نفسها)
        : [ymd(base), ymd(new Date(base.getTime() + 86400000))]; // المنطقة غرب UTC

    const budget = { n: 40 };
    const results = await Promise.allSettled(Object.keys(LEAGUES).map((s) => fetchLeague(env, s, days, budget)));
    const failed = results.filter((r) => r.status === "rejected").length;

    if (failed === results.length) {
      const stale = await cache.match(staleKey);
      if (stale) return stale;
      return json({ error: "upstream" }, 502);
    }

    const seen = new Set();
    const matches = results
      .flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .filter((m) => fmt.format(new Date(m.ts)) === date)
      .filter((m) => {
        const k = `${m.lid}|${m.ts}|${m.home}|${m.away}`;
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
      });
    const body = { matches };

    // إذا فيه مباراة مباشرة نحدّث أسرع، وإذا بعض الدوريات فشلت نكاشي قليل عشان نعيد المحاولة
    const anyLive = matches.some((m) => m.state === "in");
    const anyPre = matches.some((m) => m.state === "pre");
    const maxAge = failed || !matches.length
      ? 60
      : anyLive
      ? ttl(env, "LIVE_TTL", 60)
      : anyPre
      ? ttl(env, "UPCOMING_TTL", 600)
      : ttl(env, "DONE_TTL", 3600);

    const res = json(body, 200, { "cache-control": `public, max-age=${maxAge}` });
    ctx.waitUntil(cache.put(freshKey, res.clone()));
    if (!failed) {
      ctx.waitUntil(cache.put(staleKey, json(body, 200, { "cache-control": "public, max-age=86400" })));
    }
    return res;
  },

  // يشتغل تلقائي حسب الـ Cron Trigger (لوحة Cloudflare → الـ Worker → Triggers → Cron Triggers)
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runPrecache(env));
  },
};
