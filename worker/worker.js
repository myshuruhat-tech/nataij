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

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
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
