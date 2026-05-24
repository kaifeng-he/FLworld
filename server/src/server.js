const DEFAULT_PERSONA_ID = "emotional-support";

const DEFAULT_FEATURES = [
  { id: "chat", title: "聊天", status: "ready" },
  { id: "distance", title: "距离", status: "ready" },
  { id: "world", title: "小世界", status: "coming_soon" },
  { id: "settings", title: "设置", status: "ready" }
];

const DEFAULT_PERSONA = {
  id: DEFAULT_PERSONA_ID,
  name: "温柔情感陪伴",
  description: "你是 hkf 和 cl 的专属情感陪伴机器人。你温柔、真诚、有边界感，会支持、开导、鼓励他们，帮助他们在异地恋和大学生活中更好地表达、理解和陪伴彼此。",
  memory: "hkf 在广东上大学，cl 在四川上大学。他们是异地恋情侣。"
};

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      console.error(error);
      return json({ error: "server_error", message: "服务暂时出了点问题" }, 500);
    }
  }
};

async function route(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });

  const url = new URL(request.url);
  const pathName = trimSlash(url.pathname);
  await ensureSeedData(env.DB);

  if (request.method === "GET" && pathName === "health") {
    return json({ ok: true });
  }

  if (request.method === "POST" && pathName === "auth/login") {
    const body = await readJson(request);
    const user = users(env).find((candidate) => candidate.id === body.userId && candidate.code === body.code);
    if (!user) return json({ error: "invalid_login", message: "登录信息不正确" }, 401);
    return json({ token: user.token, user: publicUser(user) });
  }

  const user = requireUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);

  if (request.method === "GET" && pathName === "me") {
    return json({ user: publicUser(user) });
  }

  if (request.method === "GET" && pathName === "features") {
    const { results } = await env.DB.prepare("SELECT id, title, status FROM features ORDER BY sort_order").all();
    return json({ features: results });
  }

  if (request.method === "GET" && pathName === "bot/personas") {
    const { results } = await env.DB.prepare("SELECT id, name, description, memory FROM personas ORDER BY created_at").all();
    return json({ personas: results });
  }

  if (request.method === "POST" && pathName === "bot/personas") {
    const body = await readJson(request);
    const persona = {
      id: crypto.randomUUID(),
      name: requiredString(body.name, "人格名称"),
      description: requiredString(body.description, "人格描述"),
      memory: String(body.memory || "")
    };
    await env.DB.prepare(
      "INSERT INTO personas (id, name, description, memory, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(persona.id, persona.name, persona.description, persona.memory, nowIso(), nowIso()).run();
    return json({ persona }, 201);
  }

  const personaMatch = pathName.match(/^bot\/personas\/([^/]+)$/);
  if (request.method === "PUT" && personaMatch) {
    const body = await readJson(request);
    const persona = {
      id: personaMatch[1],
      name: requiredString(body.name, "人格名称"),
      description: requiredString(body.description, "人格描述"),
      memory: String(body.memory || "")
    };
    const result = await env.DB.prepare(
      "UPDATE personas SET name = ?, description = ?, memory = ?, updated_at = ? WHERE id = ?"
    ).bind(persona.name, persona.description, persona.memory, nowIso(), persona.id).run();
    if (!result.meta.changes) return json({ error: "not_found" }, 404);
    return json({ persona });
  }

  if (request.method === "GET" && pathName === "chat/sessions") {
    const { results } = await env.DB.prepare(
      "SELECT id, title, persona_id AS personaId, created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt FROM sessions ORDER BY updated_at DESC"
    ).all();
    return json({ sessions: results });
  }

  if (request.method === "POST" && pathName === "chat/sessions") {
    const body = await readJson(request);
    const personaId = body.personaId || DEFAULT_PERSONA_ID;
    const persona = await findPersona(env.DB, personaId);
    if (!persona) return json({ error: "invalid_persona" }, 400);
    const timestamp = nowIso();
    const session = {
      id: crypto.randomUUID(),
      title: String(body.title || "新的聊天").slice(0, 40),
      personaId,
      createdBy: user.id,
      createdAt: timestamp,
      updatedAt: timestamp
    };
    await env.DB.prepare(
      "INSERT INTO sessions (id, title, persona_id, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(session.id, session.title, session.personaId, session.createdBy, session.createdAt, session.updatedAt).run();
    return json({ session }, 201);
  }

  const messagesMatch = pathName.match(/^chat\/sessions\/([^/]+)\/messages$/);
  if (messagesMatch && request.method === "GET") {
    const session = await findSession(env.DB, messagesMatch[1]);
    if (!session) return json({ error: "session_not_found" }, 404);
    return json({ messages: await messagesForSession(env.DB, session.id) });
  }

  if (messagesMatch && request.method === "POST") {
    const session = await findSession(env.DB, messagesMatch[1]);
    if (!session) return json({ error: "session_not_found" }, 404);

    const body = await readJson(request);
    const text = requiredString(body.text, "消息内容");
    const timestamp = nowIso();
    const userMessage = {
      id: crypto.randomUUID(),
      sessionId: session.id,
      role: "user",
      senderId: user.id,
      text,
      createdAt: timestamp
    };
    await insertMessage(env.DB, userMessage);
    if (session.title === "新的聊天") {
      await env.DB.prepare("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?")
        .bind(titleFrom(text), timestamp, session.id)
        .run();
    } else {
      await env.DB.prepare("UPDATE sessions SET updated_at = ? WHERE id = ?").bind(timestamp, session.id).run();
    }

    const assistantText = await createAssistantReply(env, session.id, session.personaId);
    const assistantMessage = {
      id: crypto.randomUUID(),
      sessionId: session.id,
      role: "assistant",
      senderId: "bot",
      text: assistantText,
      createdAt: nowIso()
    };
    await insertMessage(env.DB, assistantMessage);
    await env.DB.prepare("UPDATE sessions SET updated_at = ? WHERE id = ?").bind(assistantMessage.createdAt, session.id).run();
    return json({ messages: [userMessage, assistantMessage] }, 201);
  }

  if (request.method === "POST" && pathName === "location/update") {
    const body = await readJson(request);
    const latitude = Number(body.latitude);
    const longitude = Number(body.longitude);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      return json({ error: "invalid_location" }, 400);
    }
    await env.DB.prepare(
      "INSERT INTO locations (user_id, latitude, longitude, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET latitude = excluded.latitude, longitude = excluded.longitude, updated_at = excluded.updated_at"
    ).bind(user.id, coarse(latitude), coarse(longitude), nowIso()).run();
    return json({ ok: true });
  }

  if (request.method === "GET" && pathName === "location/distance") {
    const mine = await findLocation(env.DB, user.id);
    const other = await findLocation(env.DB, otherUserId(user.id));
    if (!mine || !other) return json({ available: false });
    return json({ available: true, kilometers: roundedKm(distanceKm(mine, other)) });
  }

  return json({ error: "not_found" }, 404);
}

async function ensureSeedData(db) {
  const persona = await findPersona(db, DEFAULT_PERSONA.id);
  if (!persona) {
    await db.prepare(
      "INSERT INTO personas (id, name, description, memory, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(DEFAULT_PERSONA.id, DEFAULT_PERSONA.name, DEFAULT_PERSONA.description, DEFAULT_PERSONA.memory, nowIso(), nowIso()).run();
  }

  const existingFeatures = await db.prepare("SELECT COUNT(*) AS count FROM features").first();
  if (!existingFeatures?.count) {
    const statements = DEFAULT_FEATURES.map((feature, index) =>
      db.prepare("INSERT INTO features (id, title, status, sort_order) VALUES (?, ?, ?, ?)")
        .bind(feature.id, feature.title, feature.status, index)
    );
    await db.batch(statements);
  }
}

async function createAssistantReply(env, sessionId, personaId) {
  const persona = await findPersona(env.DB, personaId) || DEFAULT_PERSONA;
  const history = await messagesForSession(env.DB, sessionId, 20);
  const messages = history.map((message) => ({
    role: message.role === "assistant" ? "assistant" : "user",
    content: `${displayName(message.senderId)}：${message.text}`
  }));

  if (!env.LLM_API_KEY) {
    return "我在这里陪着你们。先慢慢说，不用一下子把所有情绪都整理好；能把感受说出来，本身就已经是在靠近彼此了。";
  }

  const baseUrl = (env.LLM_BASE_URL || "https://api.deepseek.com").replace(/\/$/, "");
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${env.LLM_API_KEY}`
    },
    body: JSON.stringify({
      model: env.LLM_MODEL || "deepseek-v4-flash",
      messages: [
        { role: "system", content: `${persona.description}\n长期记忆：${persona.memory || "暂无"}` },
        ...messages
      ],
      temperature: 0.8
    })
  });

  if (!response.ok) {
    const text = await response.text();
    console.error("LLM request failed", response.status, text);
    return "我刚刚连接模型时有点不顺，但我还是在。你们可以先把想说的话留下来，等服务恢复后我再继续陪你们聊。";
  }

  const data = await response.json();
  return data?.choices?.[0]?.message?.content?.trim() || "我听到了，也会继续陪你们把这件事慢慢说清楚。";
}

async function findPersona(db, id) {
  return db.prepare("SELECT id, name, description, memory FROM personas WHERE id = ?").bind(id).first();
}

async function findSession(db, id) {
  return db.prepare(
    "SELECT id, title, persona_id AS personaId, created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt FROM sessions WHERE id = ?"
  ).bind(id).first();
}

async function findLocation(db, userId) {
  return db.prepare("SELECT user_id AS userId, latitude, longitude FROM locations WHERE user_id = ?").bind(userId).first();
}

async function messagesForSession(db, sessionId, limit = null) {
  const sql = limit
    ? "SELECT id, session_id AS sessionId, role, sender_id AS senderId, text, created_at AS createdAt FROM messages WHERE session_id = ? ORDER BY created_at DESC LIMIT ?"
    : "SELECT id, session_id AS sessionId, role, sender_id AS senderId, text, created_at AS createdAt FROM messages WHERE session_id = ? ORDER BY created_at ASC";
  const statement = limit ? db.prepare(sql).bind(sessionId, limit) : db.prepare(sql).bind(sessionId);
  const { results } = await statement.all();
  return limit ? results.reverse() : results;
}

async function insertMessage(db, message) {
  await db.prepare(
    "INSERT INTO messages (id, session_id, role, sender_id, text, created_at) VALUES (?, ?, ?, ?, ?, ?)"
  ).bind(message.id, message.sessionId, message.role, message.senderId, message.text, message.createdAt).run();
}

function requireUser(request, env) {
  const auth = request.headers.get("authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  return users(env).find((candidate) => candidate.token === token);
}

function users(env) {
  return [
    { id: "hkf", name: "hkf", token: env.APP_TOKEN_HKF || "hkf-local-token", code: env.LOGIN_CODE_HKF || "hkf" },
    { id: "cl", name: "cl", token: env.APP_TOKEN_CL || "cl-local-token", code: env.LOGIN_CODE_CL || "cl" }
  ];
}

async function readJson(request) {
  const text = await request.text();
  return text ? JSON.parse(text) : {};
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsHeaders()
    }
  });
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS"
  };
}

function requiredString(value, label) {
  const text = String(value || "").trim();
  if (!text) throw new Error(`${label}不能为空`);
  return text;
}

function titleFrom(text) {
  return text.trim().replace(/\s+/g, " ").slice(0, 18) || "新的聊天";
}

function trimSlash(value) {
  return value.replace(/^\/+|\/+$/g, "");
}

function publicUser(user) {
  return { id: user.id, name: user.name };
}

function otherUserId(userId) {
  return userId === "hkf" ? "cl" : "hkf";
}

function displayName(senderId) {
  if (senderId === "bot") return "机器人";
  return senderId;
}

function coarse(value) {
  return Math.round(value * 100) / 100;
}

function roundedKm(value) {
  if (value < 10) return Math.round(value * 10) / 10;
  return Math.round(value);
}

function distanceKm(a, b) {
  const earthRadius = 6371;
  const dLat = toRad(b.latitude - a.latitude);
  const dLon = toRad(b.longitude - a.longitude);
  const lat1 = toRad(a.latitude);
  const lat2 = toRad(b.latitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.sqrt(h));
}

function toRad(value) {
  return (value * Math.PI) / 180;
}

function nowIso() {
  return new Date().toISOString();
}
