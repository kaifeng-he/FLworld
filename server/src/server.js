const DEFAULT_PERSONA_ID = "emotional-support";
const DEFAULT_SESSION_TITLE = "新的聊天";
const ALBUM_QUOTA_BYTES = 200 * 1024 * 1024;
const ALBUM_CHUNK_CHARS = 256 * 1024;

const DEFAULT_FEATURES = [
  { id: "distance", title: "距离", status: "ready" },
  { id: "notes", title: "我想对你说", status: "ready" },
  { id: "calendar", title: "日历", status: "ready" },
  { id: "album", title: "相册", status: "ready" }
];

const DEFAULT_PERSONA = {
  id: DEFAULT_PERSONA_ID,
  name: "温柔情感陪伴",
  description: "你是锋宝和璐宝的专属情感陪伴机器人。你温柔、真诚、有边界感，会支持、开导、鼓励他们，帮助他们在异地恋和大学生活中更好地表达、理解和陪伴彼此。",
  memory: "锋宝在广东上大学，璐宝在四川上大学。他们是异地恋情侣。内部账号 hkf 对应锋宝，cl 对应璐宝。"
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
  await ensureSchema(env.DB);
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
      title: String(body.title || DEFAULT_SESSION_TITLE).slice(0, 40),
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
    if (session.title === DEFAULT_SESSION_TITLE) {
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

  const streamMatch = pathName.match(/^chat\/sessions\/([^/]+)\/messages\/stream$/);
  if (streamMatch && request.method === "POST") {
    const session = await findSession(env.DB, streamMatch[1]);
    if (!session) return json({ error: "session_not_found", message: "没有找到这个聊天" }, 404);
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
    await env.DB.prepare("UPDATE sessions SET updated_at = ? WHERE id = ?").bind(timestamp, session.id).run();
    return streamAssistantReply(env, session, userMessage);
  }

  if (request.method === "GET" && pathName === "notes") {
    const { results } = await env.DB.prepare(
      "SELECT id, author_id AS authorId, text, created_at AS createdAt, updated_at AS updatedAt, read_at AS readAt FROM notes ORDER BY created_at DESC LIMIT 100"
    ).all();
    return json({ notes: results });
  }

  if (request.method === "POST" && pathName === "notes") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const note = {
      id: crypto.randomUUID(),
      authorId: user.id,
      text: requiredString(body.text, "留言内容").slice(0, 2000),
      createdAt: timestamp,
      updatedAt: timestamp,
      readAt: null
    };
    await env.DB.prepare(
      "INSERT INTO notes (id, author_id, text, created_at, updated_at, read_at) VALUES (?, ?, ?, ?, ?, NULL)"
    ).bind(note.id, note.authorId, note.text, note.createdAt, note.updatedAt).run();
    return json({ note }, 201);
  }

  const noteReadMatch = pathName.match(/^notes\/([^/]+)\/read$/);
  if (noteReadMatch && request.method === "POST") {
    const note = await env.DB.prepare("SELECT author_id AS authorId FROM notes WHERE id = ?").bind(noteReadMatch[1]).first();
    if (!note) return json({ error: "not_found", message: "没有找到这条留言" }, 404);
    if (note.authorId !== user.id) {
      await env.DB.prepare("UPDATE notes SET read_at = COALESCE(read_at, ?) WHERE id = ?").bind(nowIso(), noteReadMatch[1]).run();
    }
    return json({ ok: true });
  }

  if (request.method === "GET" && pathName === "calendar/events") {
    const month = url.searchParams.get("month");
    const base = "SELECT id, date, title, note, created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt FROM calendar_events";
    const statement = month
      ? env.DB.prepare(`${base} WHERE date >= ? AND date < ? ORDER BY date ASC, created_at ASC`).bind(`${month}-01`, nextMonth(month))
      : env.DB.prepare(`${base} ORDER BY date ASC, created_at ASC LIMIT 120`);
    const { results } = await statement.all();
    return json({ events: results });
  }

  if (request.method === "POST" && pathName === "calendar/events") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const event = {
      id: crypto.randomUUID(),
      date: requiredDate(body.date),
      title: requiredString(body.title, "日历标题").slice(0, 60),
      note: String(body.note || "").slice(0, 1000),
      createdBy: user.id,
      createdAt: timestamp,
      updatedAt: timestamp
    };
    await env.DB.prepare(
      "INSERT INTO calendar_events (id, date, title, note, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
    ).bind(event.id, event.date, event.title, event.note, event.createdBy, event.createdAt, event.updatedAt).run();
    return json({ event }, 201);
  }

  const calendarMatch = pathName.match(/^calendar\/events\/([^/]+)$/);
  if (calendarMatch && request.method === "PUT") {
    const body = await readJson(request);
    const current = await env.DB.prepare("SELECT created_by AS createdBy FROM calendar_events WHERE id = ?").bind(calendarMatch[1]).first();
    if (!current) return json({ error: "not_found", message: "没有找到这个日历事项" }, 404);
    const event = {
      id: calendarMatch[1],
      date: requiredDate(body.date),
      title: requiredString(body.title, "日历标题").slice(0, 60),
      note: String(body.note || "").slice(0, 1000),
      createdBy: current.createdBy,
      updatedAt: nowIso()
    };
    const result = await env.DB.prepare(
      "UPDATE calendar_events SET date = ?, title = ?, note = ?, updated_at = ? WHERE id = ?"
    ).bind(event.date, event.title, event.note, event.updatedAt, event.id).run();
    return json({ event });
  }

  if (calendarMatch && request.method === "DELETE") {
    const result = await env.DB.prepare("DELETE FROM calendar_events WHERE id = ?").bind(calendarMatch[1]).run();
    if (!result.meta.changes) return json({ error: "not_found", message: "没有找到这个日历事项" }, 404);
    return json({ ok: true });
  }

  if (request.method === "GET" && pathName === "album") {
    const { results } = await env.DB.prepare(
      "SELECT id, uploader_id AS uploaderId, media_type AS mediaType, mime_type AS mimeType, file_name AS fileName, byte_size AS byteSize, created_at AS createdAt FROM album_items ORDER BY created_at DESC LIMIT 100"
    ).all();
    const usedBytes = await albumUsedBytes(env.DB);
    return json({ items: results, quota: { usedBytes, limitBytes: ALBUM_QUOTA_BYTES } });
  }

  if (request.method === "POST" && pathName === "album") {
    const body = await readJson(request);
    const dataBase64 = requiredString(body.dataBase64, "相册内容");
    const byteSize = Number(body.byteSize);
    if (!Number.isInteger(byteSize) || byteSize <= 0) return json({ error: "invalid_media", message: "文件大小不正确" }, 400);
    const usedBytes = await albumUsedBytes(env.DB);
    if (usedBytes + byteSize > ALBUM_QUOTA_BYTES) {
      return json({ error: "album_quota_exceeded", message: "相册空间已经不够了，可以先删除一些旧照片或视频" }, 413);
    }
    const mimeType = String(body.mimeType || "application/octet-stream").slice(0, 120);
    const mediaType = mimeType.startsWith("video/") ? "video" : "image";
    const timestamp = nowIso();
    const item = {
      id: crypto.randomUUID(),
      uploaderId: user.id,
      mediaType,
      mimeType,
      fileName: String(body.fileName || "珍贵回忆").slice(0, 120),
      byteSize,
      createdAt: timestamp
    };
    if (await albumItemsHasInlineData(env.DB)) {
      await env.DB.prepare(
        "INSERT INTO album_items (id, uploader_id, media_type, mime_type, file_name, byte_size, data_base64, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
      ).bind(item.id, item.uploaderId, item.mediaType, item.mimeType, item.fileName, item.byteSize, dataBase64, item.createdAt).run();
    } else {
      await env.DB.prepare(
        "INSERT INTO album_items (id, uploader_id, media_type, mime_type, file_name, byte_size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
      ).bind(item.id, item.uploaderId, item.mediaType, item.mimeType, item.fileName, item.byteSize, item.createdAt).run();
    }
    await insertAlbumChunks(env.DB, item.id, dataBase64);
    return json({ item, quota: { usedBytes: usedBytes + byteSize, limitBytes: ALBUM_QUOTA_BYTES } }, 201);
  }

  const albumMatch = pathName.match(/^album\/([^/]+)$/);
  if (albumMatch && request.method === "GET") {
    const hasInlineData = await albumItemsHasInlineData(env.DB);
    const item = await env.DB.prepare(
      hasInlineData
        ? "SELECT id, uploader_id AS uploaderId, media_type AS mediaType, mime_type AS mimeType, file_name AS fileName, byte_size AS byteSize, data_base64 AS inlineDataBase64, created_at AS createdAt FROM album_items WHERE id = ?"
        : "SELECT id, uploader_id AS uploaderId, media_type AS mediaType, mime_type AS mimeType, file_name AS fileName, byte_size AS byteSize, created_at AS createdAt FROM album_items WHERE id = ?"
    ).bind(albumMatch[1]).first();
    if (!item) return json({ error: "not_found", message: "没有找到这段回忆" }, 404);
    item.dataBase64 = await albumData(env.DB, item.id) || item.inlineDataBase64 || "";
    delete item.inlineDataBase64;
    return json({ item });
  }

  if (albumMatch && request.method === "DELETE") {
    await env.DB.prepare("DELETE FROM album_chunks WHERE item_id = ?").bind(albumMatch[1]).run();
    const result = await env.DB.prepare("DELETE FROM album_items WHERE id = ?").bind(albumMatch[1]).run();
    if (!result.meta.changes) return json({ error: "not_found", message: "没有找到这段回忆" }, 404);
    return json({ ok: true });
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

async function ensureSchema(db) {
  await db.batch([
    db.prepare(
      "CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, author_id TEXT NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, read_at TEXT)"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS calendar_events (id TEXT PRIMARY KEY, date TEXT NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created_by TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS album_items (id TEXT PRIMARY KEY, uploader_id TEXT NOT NULL, media_type TEXT NOT NULL, mime_type TEXT NOT NULL, file_name TEXT NOT NULL, byte_size INTEGER NOT NULL, created_at TEXT NOT NULL)"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS album_chunks (item_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, data_base64 TEXT NOT NULL, PRIMARY KEY (item_id, chunk_index), FOREIGN KEY (item_id) REFERENCES album_items(id) ON DELETE CASCADE)"
    ),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes(created_at)"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_calendar_events_date ON calendar_events(date)"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_album_items_created_at ON album_items(created_at)")
  ]);
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

function streamAssistantReply(env, session, userMessage) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      let assistantText = "";
      const send = (event, data) => {
        controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
      };
      try {
        send("user", { message: userMessage });
        if (!env.LLM_API_KEY) {
          const fallback = "我在这里陪着你们。先慢慢说，不用一下子把所有情绪都整理好；能把感受说出来，本身就已经是在靠近彼此了。";
          for (const chunk of fallback.match(/.{1,8}/gu) || [fallback]) {
            assistantText += chunk;
            send("chunk", { text: chunk });
          }
        } else {
          assistantText = await streamFromModel(env, session, send);
        }

        const assistantMessage = {
          id: crypto.randomUUID(),
          sessionId: session.id,
          role: "assistant",
          senderId: "bot",
          text: assistantText.trim() || "我听到了，也会继续陪你们把这件事慢慢说清楚。",
          createdAt: nowIso()
        };
        await insertMessage(env.DB, assistantMessage);

        let title = session.title;
        if (session.title === DEFAULT_SESSION_TITLE) {
          title = await createSessionTitle(env, userMessage.text, assistantMessage.text);
          await env.DB.prepare("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?")
            .bind(title, assistantMessage.createdAt, session.id)
            .run();
        } else {
          await env.DB.prepare("UPDATE sessions SET updated_at = ? WHERE id = ?").bind(assistantMessage.createdAt, session.id).run();
        }
        send("done", { message: assistantMessage, title });
      } catch (error) {
        console.error(error);
        send("error", { message: "回复生成时出了点问题" });
      } finally {
        controller.close();
      }
    }
  });
  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      ...corsHeaders()
    }
  });
}

async function streamFromModel(env, session, send) {
  const persona = await findPersona(env.DB, session.personaId) || DEFAULT_PERSONA;
  const history = await messagesForSession(env.DB, session.id, 20);
  const messages = history.map((message) => ({
    role: message.role === "assistant" ? "assistant" : "user",
    content: `${displayName(message.senderId)}：${message.text}`
  }));
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
      temperature: 0.8,
      stream: true
    })
  });
  if (!response.ok || !response.body) {
    const text = await response.text();
    console.error("LLM stream failed", response.status, text);
    const fallback = "我刚刚连接模型时有点不顺，但我还是在。你们可以先把想说的话留下来，等服务恢复后我再继续陪你们聊。";
    send("chunk", { text: fallback });
    return fallback;
  }

  let assistantText = "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith("data:")) continue;
      const payload = trimmed.slice(5).trim();
      if (!payload || payload === "[DONE]") continue;
      try {
        const data = JSON.parse(payload);
        const chunk = data?.choices?.[0]?.delta?.content || "";
        if (chunk) {
          assistantText += chunk;
          send("chunk", { text: chunk });
        }
      } catch {
        // Ignore malformed provider stream fragments.
      }
    }
  }
  return assistantText;
}

async function createSessionTitle(env, userText, assistantText) {
  if (!env.LLM_API_KEY) return titleFrom(userText);
  const baseUrl = (env.LLM_BASE_URL || "https://api.deepseek.com").replace(/\/$/, "");
  try {
    const response = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${env.LLM_API_KEY}`
      },
      body: JSON.stringify({
        model: env.LLM_MODEL || "deepseek-v4-flash",
        messages: [
          { role: "system", content: "请根据这段对话生成一个简短中文标题，不超过12个字，只输出标题本身。" },
          { role: "user", content: `用户：${userText}\n助手：${assistantText}` }
        ],
        temperature: 0.4
      })
    });
    if (!response.ok) return titleFrom(userText);
    const data = await response.json();
    return cleanTitle(data?.choices?.[0]?.message?.content) || titleFrom(userText);
  } catch {
    return titleFrom(userText);
  }
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
    { id: "hkf", name: "锋宝", token: env.APP_TOKEN_HKF || "hkf-local-token", code: env.LOGIN_CODE_HKF || "hkf" },
    { id: "cl", name: "璐宝", token: env.APP_TOKEN_CL || "cl-local-token", code: env.LOGIN_CODE_CL || "cl" }
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
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS"
  };
}

function requiredString(value, label) {
  const text = String(value || "").trim();
  if (!text) throw new Error(`${label}不能为空`);
  return text;
}

function requiredDate(value) {
  const text = requiredString(value, "日期");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) throw new Error("日期格式不正确");
  return text;
}

function titleFrom(text) {
  return text.trim().replace(/\s+/g, " ").slice(0, 18) || "新的聊天";
}

function cleanTitle(text) {
  return String(text || "")
    .replace(/[《》"'“”]/g, "")
    .trim()
    .slice(0, 18);
}

function nextMonth(month) {
  if (!/^\d{4}-\d{2}$/.test(month)) return "9999-12-31";
  const [year, rawMonth] = month.split("-").map(Number);
  const date = new Date(Date.UTC(year, rawMonth, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-01`;
}

async function albumUsedBytes(db) {
  const row = await db.prepare("SELECT COALESCE(SUM(byte_size), 0) AS usedBytes FROM album_items").first();
  return Number(row?.usedBytes || 0);
}

async function insertAlbumChunks(db, itemId, dataBase64) {
  const statements = [];
  for (let index = 0; index * ALBUM_CHUNK_CHARS < dataBase64.length; index += 1) {
    statements.push(
      db.prepare("INSERT INTO album_chunks (item_id, chunk_index, data_base64) VALUES (?, ?, ?)")
        .bind(itemId, index, dataBase64.slice(index * ALBUM_CHUNK_CHARS, (index + 1) * ALBUM_CHUNK_CHARS))
    );
  }
  for (let index = 0; index < statements.length; index += 50) {
    await db.batch(statements.slice(index, index + 50));
  }
}

async function albumData(db, itemId) {
  const { results } = await db.prepare("SELECT data_base64 AS dataBase64 FROM album_chunks WHERE item_id = ? ORDER BY chunk_index")
    .bind(itemId)
    .all();
  return results.map((row) => row.dataBase64).join("");
}

async function albumItemsHasInlineData(db) {
  const { results } = await db.prepare("PRAGMA table_info(album_items)").all();
  return results.some((column) => column.name === "data_base64");
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
  if (senderId === "hkf") return "锋宝";
  if (senderId === "cl") return "璐宝";
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
