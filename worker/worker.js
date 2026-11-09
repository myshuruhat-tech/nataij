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
};

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

async function fetchLeague(slug, from, to) {
  const r = await fetch(`${ESPN}/${slug}/scoreboard?dates=${from}-${to}&limit=300`, {
    headers: { "user-agent": "Mozilla/5.0", accept: "application/json" },
  });
  if (!r.ok) throw new Error(`http ${r.status}`);
  const data = await r.json();
  return (data.events || []).map((ev) => normalize(ev, LEAGUES[slug]));
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
  const key = `pair:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
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
  const key = `usage:${pair}`;
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
  const key = `activity:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    return new Response(raw || '{"entries":[]}', { headers: { "content-type": "application/json; charset=utf-8", ...noStore } });
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
    const raw = await env.LISTS.get(key);
    let store = { entries: [] };
    if (raw) {
      try { store = JSON.parse(raw); if (!Array.isArray(store.entries)) store.entries = []; } catch (e) { store = { entries: [] }; }
    }
    store.entries.unshift({ ts: new Date().toISOString(), type, kind, channel, label });
    store.entries = store.entries.slice(0, MAX_ACTIVITY_ENTRIES);
    await env.LISTS.put(key, JSON.stringify(store));
    return json({ ok: true }, 200, noStore);
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

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/blocklist") return handleBlocklist(request, env, url);
    if (url.pathname === "/usage") return handleUsage(request, env, url);
    if (url.pathname === "/searchlog") return handleSearchLog(request, env, url);
    if (url.pathname === "/activity") return handleActivity(request, env, url);
    if (url.pathname === "/match") return handleMatch(url);
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

    // الكاش حسب (التاريخ + المنطقة) فقط، فكل المستخدمين يشاركون نفس الطلب
    const cache = caches.default;
    const freshKey = new Request(`https://cache.nataij/fresh/${date}/${tz}`);
    const staleKey = new Request(`https://cache.nataij/stale/${date}/${tz}`);

    const hit = await cache.match(freshKey);
    if (hit) return hit;

    // ESPN يقسّم الأيام بتوقيت أمريكا، فنجلب يوم قبل ويوم بعد ثم نفلتر بتاريخ منطقة المستخدم
    const base = new Date(`${date}T12:00:00Z`);
    const from = ymd(new Date(base.getTime() - 86400000));
    const to = ymd(new Date(base.getTime() + 86400000));

    const results = await Promise.allSettled(Object.keys(LEAGUES).map((s) => fetchLeague(s, from, to)));
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
    const maxAge = failed
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
};
