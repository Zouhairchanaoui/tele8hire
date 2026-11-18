// Edge Function: push-message
// يُستدعى من Trigger في قاعدة البيانات عند كل رسالة جديدة، ويرسل إشعار FCM للمستلم.
// الأسرار المطلوبة: FIREBASE_SERVICE_ACCOUNT (محتوى ملف JSON لحساب الخدمة).
import { createClient } from "npm:@supabase/supabase-js@2";

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

const enc = new TextEncoder();
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json" } });

const b64url = (input: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof input === "string" ? enc.encode(input) : new Uint8Array(input);
  let s = "";
  bytes.forEach((x) => (s += String.fromCharCode(x)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

let cached: { token: string; exp: number } | null = null;

async function accessToken(sa: { client_email: string; private_key: string }) {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;

  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/\\n/g, "\n").replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claim}`));
  const assertion = `${header}.${claim}.${b64url(sig)}`;

  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const j = await r.json();
  if (!r.ok) throw new Error("oauth_failed: " + JSON.stringify(j));
  cached = { token: j.access_token, exp: now + (j.expires_in ?? 3600) };
  return cached.token;
}

Deno.serve(async (req) => {
  try {
    const { message_id } = await req.json();
    if (!message_id || !/^[0-9a-f-]{36}$/i.test(message_id)) return json({ ok: false, error: "bad_request" }, 400);

    const { data: m } = await admin
      .from("messages")
      .select("id,conversation_id,sender_id,body,created_at,deleted_all")
      .eq("id", message_id)
      .maybeSingle();
    if (!m) return json({ ok: true, skipped: "not_found" });
    if (m.deleted_all) return json({ ok: true, skipped: "deleted" });
    if (Date.now() - new Date(m.created_at).getTime() > 5 * 60 * 1000) return json({ ok: true, skipped: "stale" });

    const { data: conv } = await admin
      .from("conversations")
      .select("user_a,user_b")
      .eq("id", m.conversation_id)
      .maybeSingle();
    if (!conv) return json({ ok: true, skipped: "no_conversation" });
    const recipient = conv.user_a === m.sender_id ? conv.user_b : conv.user_a;

    const { data: blocked } = await admin
      .from("blocks")
      .select("blocker_id")
      .eq("blocker_id", recipient)
      .eq("blocked_id", m.sender_id)
      .maybeSingle();
    if (blocked) return json({ ok: true, skipped: "blocked" });

    // منع التكرار: كل رسالة تُرسل مرة واحدة فقط
    const { error: logErr } = await admin.from("push_log").insert({ message_id });
    if (logErr) return json({ ok: true, skipped: "duplicate" });

    const [{ data: sender }, { data: tokens }] = await Promise.all([
      admin.from("profiles").select("full_name,avatar_url").eq("id", m.sender_id).maybeSingle(),
      admin.from("device_tokens").select("token").eq("user_id", recipient),
    ]);
    if (!tokens || tokens.length === 0) return json({ ok: true, skipped: "no_tokens" });

    const saRaw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
    if (!saRaw) return json({ ok: false, error: "missing_FIREBASE_SERVICE_ACCOUNT" }, 500);
    const sa = JSON.parse(saRaw);
    const bearer = await accessToken(sa);

    const body = String(m.body ?? "").slice(0, 180);
    let sent = 0;
    const dead: string[] = [];

    await Promise.all(tokens.map(async ({ token }: { token: string }) => {
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            data: {
              conversation_id: String(m.conversation_id),
              message_id: String(m.id),
              sender_id: String(m.sender_id),
              sender_name: String(sender?.full_name ?? ""),
              sender_avatar: String(sender?.avatar_url ?? ""),
              body,
            },
            android: { priority: "HIGH", ttl: "86400s", collapse_key: String(m.id) },
          },
        }),
      });
      if (r.ok) sent++;
      else {
        const e = await r.text();
        if (r.status === 404 || e.includes("UNREGISTERED")) dead.push(token);
        else console.error("fcm_error", r.status, e);
      }
    }));

    if (dead.length) await admin.from("device_tokens").delete().in("token", dead);
    return json({ ok: true, sent, removed: dead.length });
  } catch (e) {
    console.error(e);
    return json({ ok: false, error: String(e) }, 500);
  }
});
