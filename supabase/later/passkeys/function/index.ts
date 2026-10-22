// Passkey (WebAuthn) registration and sign-in for the Daedalus companion app.
//
// Routes (POST, JSON):
//   /passkeys/register-options  (signed in)  -> { challengeId, options }
//   /passkeys/register-verify   (signed in)  { challengeId, response } -> { ok }
//   /passkeys/login-options                  -> { challengeId, options }
//   /passkeys/login-verify                   { challengeId, response } -> Supabase session
//
// Environment (supabase secrets set …):
//   PASSKEY_RP_ID     domain that hosts /.well-known/assetlinks.json (e.g. daedalus.example.com)
//   PASSKEY_RP_NAME   shown in the passkey prompt (default "Daedalus")
//   PASSKEY_ORIGINS   comma-separated allowed origins, e.g.
//                     android:apk-key-hash:<base64url SHA-256 of the app signing cert>
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are provided by Supabase.

import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
} from "npm:@simplewebauthn/server@10.0.1";
import { isoBase64URL } from "npm:@simplewebauthn/server@10.0.1/helpers";

const rpID = Deno.env.get("PASSKEY_RP_ID") ?? "";
const rpName = Deno.env.get("PASSKEY_RP_NAME") ?? "Daedalus";
const origins = (Deno.env.get("PASSKEY_ORIGINS") ?? "").split(",").map((s) => s.trim()).filter(Boolean);

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
const admin = createClient(supabaseUrl, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** The signed-in user from the request's bearer token, or null. */
async function currentUser(req: Request) {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token || token === anonKey) return null;
  const { data, error } = await admin.auth.getUser(token);
  return error ? null : data.user;
}

async function saveChallenge(challenge: string, kind: "register" | "login", userId: string | null) {
  const { data, error } = await admin
    .from("passkey_challenges")
    .insert({ challenge, kind, user_id: userId })
    .select("id")
    .single();
  if (error) throw error;
  return data.id as string;
}

/** Returns and deletes a challenge (one use only), if it exists and has not expired. */
async function takeChallenge(id: string, kind: "register" | "login") {
  const { data } = await admin.from("passkey_challenges").delete().eq("id", id).eq("kind", kind).select().maybeSingle();
  if (!data || new Date(data.expires_at) < new Date()) return null;
  return data as { challenge: string; user_id: string | null };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!rpID || origins.length === 0) return json({ error: "passkeys_not_configured" }, 503);
  const route = new URL(req.url).pathname.split("/").pop();
  const body = await req.json().catch(() => ({}));

  try {
    switch (route) {
      case "register-options": {
        const user = await currentUser(req);
        if (!user) return json({ error: "unauthorized" }, 401);
        const { data: existing } = await admin.from("passkey_credentials").select("id, transports").eq("user_id", user.id);
        const options = await generateRegistrationOptions({
          rpName,
          rpID,
          userName: user.email ?? user.id,
          userID: new TextEncoder().encode(user.id),
          attestationType: "none",
          excludeCredentials: (existing ?? []).map((c) => ({ id: c.id, transports: c.transports })),
          authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
        });
        const challengeId = await saveChallenge(options.challenge, "register", user.id);
        return json({ challengeId, options });
      }

      case "register-verify": {
        const user = await currentUser(req);
        if (!user) return json({ error: "unauthorized" }, 401);
        const stored = await takeChallenge(body.challengeId, "register");
        if (!stored || stored.user_id !== user.id) return json({ error: "challenge_expired" }, 400);
        const result = await verifyRegistrationResponse({
          response: body.response,
          expectedChallenge: stored.challenge,
          expectedOrigin: origins,
          expectedRPID: rpID,
        });
        if (!result.verified || !result.registrationInfo) return json({ error: "not_verified" }, 400);
        const info = result.registrationInfo;
        const { error } = await admin.from("passkey_credentials").insert({
          id: info.credentialID,
          user_id: user.id,
          public_key: isoBase64URL.fromBuffer(info.credentialPublicKey),
          counter: info.counter,
          transports: body.response?.response?.transports ?? [],
          device_name: req.headers.get("User-Agent")?.slice(0, 120) ?? null,
        });
        if (error) throw error;
        return json({ ok: true });
      }

      case "login-options": {
        // Discoverable credentials: the phone offers the passkeys it has for this site.
        const options = await generateAuthenticationOptions({ rpID, userVerification: "preferred" });
        const challengeId = await saveChallenge(options.challenge, "login", null);
        return json({ challengeId, options });
      }

      case "login-verify": {
        const stored = await takeChallenge(body.challengeId, "login");
        if (!stored) return json({ error: "challenge_expired" }, 400);
        const { data: cred } = await admin.from("passkey_credentials").select("*").eq("id", body.response?.id).maybeSingle();
        if (!cred) return json({ error: "unknown_passkey" }, 400);
        const result = await verifyAuthenticationResponse({
          response: body.response,
          expectedChallenge: stored.challenge,
          expectedOrigin: origins,
          expectedRPID: rpID,
          authenticator: {
            credentialID: cred.id,
            credentialPublicKey: isoBase64URL.toBuffer(cred.public_key),
            counter: Number(cred.counter),
            transports: cred.transports,
          },
        });
        if (!result.verified) return json({ error: "not_verified" }, 400);
        await admin
          .from("passkey_credentials")
          .update({ counter: result.authenticationInfo.newCounter, last_used_at: new Date().toISOString() })
          .eq("id", cred.id);

        // Issue a normal Supabase session for this user (no email is sent).
        const { data: userData, error: userError } = await admin.auth.admin.getUserById(cred.user_id);
        if (userError || !userData.user?.email) return json({ error: "user_not_found" }, 400);
        const { data: link, error: linkError } = await admin.auth.admin.generateLink({
          type: "magiclink",
          email: userData.user.email,
        });
        if (linkError) throw linkError;
        const anon = createClient(supabaseUrl, anonKey, { auth: { persistSession: false } });
        const { data: verified, error: verifyError } = await anon.auth.verifyOtp({
          type: "magiclink",
          token_hash: link.properties.hashed_token,
        });
        if (verifyError || !verified.session) throw verifyError ?? new Error("no session");
        const s = verified.session;
        return json({
          access_token: s.access_token,
          refresh_token: s.refresh_token,
          expires_in: s.expires_in,
          user: { id: s.user.id, email: s.user.email },
        });
      }

      default:
        return json({ error: "not_found" }, 404);
    }
  } catch (e) {
    console.error(e);
    return json({ error: "server_error" }, 500);
  }
});
