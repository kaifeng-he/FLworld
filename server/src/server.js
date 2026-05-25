import http from "node:http";
import tcb from "@cloudbase/node-sdk";

const DEFAULT_PERSONA_ID = "emotional-support";
const DEFAULT_SESSION_TITLE = "新的聊天";
const ALBUM_QUOTA_BYTES = 200 * 1024 * 1024;
const MAX_REQUEST_BYTES = 280 * 1024 * 1024;
const DEFAULT_BOT_BUBBLE_COLOR = "#FFE0A8";
const PORT = Number(process.env.PORT || 9000);

const DEFAULT_FEATURES = [
  { id: "distance", title: "距离", status: "ready", sortOrder: 0 },
  { id: "notes", title: "我想对你说", status: "ready", sortOrder: 1 },
  { id: "calendar", title: "日历", status: "ready", sortOrder: 2 },
  { id: "album", title: "相册", status: "ready", sortOrder: 3 }
];

const DEFAULT_PERSONA = {
  id: DEFAULT_PERSONA_ID,
  name: "温柔情感陪伴",
  description: "你是恺锋和小璐的专属情感陪伴机器人。你温柔、真诚、有边界感，会支持、开导、鼓励他们，帮助他们在异地恋和大学生活中更好地表达、理解和陪伴彼此。",
  memory: "恺锋在广东上大学，小璐在四川上大学。他们是异地恋情侣。内部账号 hkf 对应恺锋，cl 对应小璐。",
  bubbleColor: DEFAULT_BOT_BUBBLE_COLOR
};

const app = tcb.init({
  env: process.env.TCB_ENV_ID || tcb.SYMBOL_DEFAULT_ENV,
  timeout: 120000
});
const db = app.database();

const collections = {
  features: db.collection("features"),
  personas: db.collection("personas"),
  sessions: db.collection("sessions"),
  messages: db.collection("messages"),
  notes: db.collection("notes"),
  calendarEvents: db.collection("calendar_events"),
  albumItems: db.collection("album_items"),
  locations: db.collection("locations")
};

const server = http.createServer(async (request, response) => {
  try {
    await route(request, response);
  } catch (error) {
    console.error(error);
    if (!response.headersSent) {
      sendJson(response, { error: "server_error", message: error.message || "服务暂时出了点问题" }, 500);
    } else if (!response.writableEnded) {
      response.end();
    }
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`FL小世界 CloudBase API listening on ${PORT}`);
});

async function route(request, response) {
  if (request.method === "OPTIONS") {
    response.writeHead(204, corsHeaders());
    response.end();
    return;
  }

  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
  const pathName = trimSlash(url.pathname);

  if (request.method === "GET" && pathName === "health") {
    sendJson(response, { ok: true });
    return;
  }

  await ensureSeedData();

  if (request.method === "POST" && pathName === "auth/login") {
    const body = await readJson(request);
    const user = users().find((candidate) => candidate.id === body.userId && candidate.code === body.code);
    if (!user) return sendJson(response, { error: "invalid_login", message: "登录信息不正确" }, 401);
    return sendJson(response, { token: user.token, user: publicUser(user) });
  }

  const user = requireUser(request);
  if (!user) return sendJson(response, { error: "unauthorized" }, 401);

  if (request.method === "GET" && pathName === "me") {
    return sendJson(response, { user: publicUser(user) });
  }

  if (request.method === "GET" && pathName === "features") {
    const features = await query(collections.features, null, "sortOrder", "asc");
    return sendJson(response, { features: features.map(publicFeature) });
  }

  if (request.method === "GET" && pathName === "bot/personas") {
    const personas = await query(collections.personas, null, "createdAt", "asc");
    return sendJson(response, { personas: personas.map(publicPersona) });
  }

  if (request.method === "POST" && pathName === "bot/personas") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const persona = {
      id: randomId(),
      name: requiredString(body.name, "人格名称"),
      description: requiredString(body.description, "人格描述"),
      memory: String(body.memory || ""),
      bubbleColor: normalizeColor(body.bubbleColor),
      createdAt: timestamp,
      updatedAt: timestamp
    };
    await put(collections.personas, persona.id, persona);
    return sendJson(response, { persona: publicPersona(persona) }, 201);
  }

  const personaMatch = pathName.match(/^bot\/personas\/([^/]+)$/);
  if (request.method === "PUT" && personaMatch) {
    if (personaMatch[1] === DEFAULT_PERSONA_ID) {
      return sendJson(response, { error: "default_persona", message: "默认聊天风格不能编辑" }, 400);
    }
    const current = await get(collections.personas, personaMatch[1]);
    if (!current) return sendJson(response, { error: "not_found" }, 404);
    const body = await readJson(request);
    const persona = {
      ...current,
      id: personaMatch[1],
      name: requiredString(body.name, "人格名称"),
      description: requiredString(body.description, "人格描述"),
      memory: String(body.memory || ""),
      bubbleColor: normalizeColor(body.bubbleColor),
      updatedAt: nowIso()
    };
    await put(collections.personas, persona.id, persona);
    return sendJson(response, { persona: publicPersona(persona) });
  }

  if (request.method === "DELETE" && personaMatch) {
    const personaId = personaMatch[1];
    if (personaId === DEFAULT_PERSONA_ID) {
      return sendJson(response, { error: "default_persona", message: "默认聊天风格不能删除" }, 400);
    }
    if (!(await get(collections.personas, personaId))) {
      return sendJson(response, { error: "not_found", message: "没有找到这个聊天风格" }, 404);
    }
    const sessions = await query(collections.sessions, { personaId });
    await Promise.all(sessions.map((session) => put(collections.sessions, session.id, { ...session, personaId: DEFAULT_PERSONA_ID })));
    await collections.personas.doc(personaId).remove();
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "chat/sessions") {
    const sessions = await query(collections.sessions, null, "updatedAt", "desc");
    return sendJson(response, { sessions: sessions.map(publicSession) });
  }

  if (request.method === "POST" && pathName === "chat/sessions") {
    const body = await readJson(request);
    const personaId = body.personaId || DEFAULT_PERSONA_ID;
    if (!(await get(collections.personas, personaId))) return sendJson(response, { error: "invalid_persona" }, 400);
    const timestamp = nowIso();
    const session = {
      id: randomId(),
      title: String(body.title || DEFAULT_SESSION_TITLE).slice(0, 40),
      personaId,
      createdBy: user.id,
      createdAt: timestamp,
      updatedAt: timestamp
    };
    await put(collections.sessions, session.id, session);
    return sendJson(response, { session: publicSession(session) }, 201);
  }

  const sessionMatch = pathName.match(/^chat\/sessions\/([^/]+)$/);
  if (request.method === "DELETE" && sessionMatch) {
    if (!(await get(collections.sessions, sessionMatch[1]))) {
      return sendJson(response, { error: "session_not_found", message: "没有找到这个聊天" }, 404);
    }
    await collections.messages.where({ sessionId: sessionMatch[1] }).remove();
    await collections.sessions.doc(sessionMatch[1]).remove();
    return sendJson(response, { ok: true });
  }

  const messagesMatch = pathName.match(/^chat\/sessions\/([^/]+)\/messages$/);
  if (messagesMatch && request.method === "GET") {
    if (!(await get(collections.sessions, messagesMatch[1]))) return sendJson(response, { error: "session_not_found" }, 404);
    return sendJson(response, { messages: await messagesForSession(messagesMatch[1]) });
  }

  if (messagesMatch && request.method === "POST") {
    const session = await get(collections.sessions, messagesMatch[1]);
    if (!session) return sendJson(response, { error: "session_not_found" }, 404);
    const userMessage = await createUserMessage(session, user, await readJson(request));
    const assistantText = await createAssistantReply(session.id, session.personaId);
    const assistantMessage = await saveAssistantMessage(session, assistantText);
    return sendJson(response, { messages: [userMessage, assistantMessage] }, 201);
  }

  const streamMatch = pathName.match(/^chat\/sessions\/([^/]+)\/messages\/stream$/);
  if (streamMatch && request.method === "POST") {
    const session = await get(collections.sessions, streamMatch[1]);
    if (!session) return sendJson(response, { error: "session_not_found", message: "没有找到这个聊天" }, 404);
    const userMessage = await createUserMessage(session, user, await readJson(request), false);
    return streamAssistantReply(response, session, userMessage);
  }

  if (request.method === "GET" && pathName === "notes") {
    const notes = await query(collections.notes, null, "createdAt", "desc");
    return sendJson(response, { notes: notes.map(publicNote) });
  }

  if (request.method === "POST" && pathName === "notes") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const note = {
      id: randomId(),
      authorId: user.id,
      text: requiredString(body.text, "留言内容").slice(0, 2000),
      createdAt: timestamp,
      updatedAt: timestamp,
      readAt: null
    };
    await put(collections.notes, note.id, note);
    return sendJson(response, { note: publicNote(note) }, 201);
  }

  const noteReadMatch = pathName.match(/^notes\/([^/]+)\/read$/);
  if (noteReadMatch && request.method === "POST") {
    const note = await get(collections.notes, noteReadMatch[1]);
    if (!note) return sendJson(response, { error: "not_found", message: "没有找到这条留言" }, 404);
    if (note.authorId !== user.id && !note.readAt) {
      await put(collections.notes, note.id, { ...note, readAt: nowIso() });
    }
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "calendar/events") {
    const month = url.searchParams.get("month");
    let events = await query(collections.calendarEvents, null, "date", "asc");
    if (month) events = events.filter((event) => event.date >= `${month}-01` && event.date < nextMonth(month));
    return sendJson(response, { events: events.map(publicCalendarEvent) });
  }

  if (request.method === "POST" && pathName === "calendar/events") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const event = {
      id: randomId(),
      date: requiredDate(body.date),
      title: requiredString(body.title, "日历标题").slice(0, 60),
      note: String(body.note || "").slice(0, 1000),
      createdBy: user.id,
      createdAt: timestamp,
      updatedAt: timestamp
    };
    await put(collections.calendarEvents, event.id, event);
    return sendJson(response, { event: publicCalendarEvent(event) }, 201);
  }

  const calendarMatch = pathName.match(/^calendar\/events\/([^/]+)$/);
  if (calendarMatch && request.method === "PUT") {
    const current = await get(collections.calendarEvents, calendarMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这个日历事项" }, 404);
    const body = await readJson(request);
    const event = {
      ...current,
      id: calendarMatch[1],
      date: requiredDate(body.date),
      title: requiredString(body.title, "日历标题").slice(0, 60),
      note: String(body.note || "").slice(0, 1000),
      updatedAt: nowIso()
    };
    await put(collections.calendarEvents, event.id, event);
    return sendJson(response, { event: publicCalendarEvent(event) });
  }

  if (calendarMatch && request.method === "DELETE") {
    if (!(await get(collections.calendarEvents, calendarMatch[1]))) {
      return sendJson(response, { error: "not_found", message: "没有找到这个日历事项" }, 404);
    }
    await collections.calendarEvents.doc(calendarMatch[1]).remove();
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "album") {
    const items = await query(collections.albumItems, null, "createdAt", "desc");
    return sendJson(response, {
      items: items.slice(0, 100).map(publicAlbumItem),
      quota: { usedBytes: albumUsedBytes(items), limitBytes: ALBUM_QUOTA_BYTES }
    });
  }

  if (request.method === "POST" && pathName === "album") {
    const body = await readJson(request);
    const dataBase64 = requiredString(body.dataBase64, "相册内容");
    const byteSize = Number(body.byteSize);
    if (!Number.isInteger(byteSize) || byteSize <= 0) {
      return sendJson(response, { error: "invalid_media", message: "文件大小不正确" }, 400);
    }
    const fileContent = Buffer.from(dataBase64, "base64");
    if (fileContent.length !== byteSize) {
      return sendJson(response, { error: "invalid_media", message: "文件内容和大小不匹配" }, 400);
    }
    const currentItems = await query(collections.albumItems);
    const usedBytes = albumUsedBytes(currentItems);
    if (usedBytes + byteSize > ALBUM_QUOTA_BYTES) {
      return sendJson(response, { error: "album_quota_exceeded", message: "相册空间已经不够了，可以先删除一些旧照片或视频" }, 413);
    }
    const mimeType = String(body.mimeType || "application/octet-stream").slice(0, 120);
    const item = {
      id: randomId(),
      uploaderId: user.id,
      mediaType: mimeType.startsWith("video/") ? "video" : "image",
      mimeType,
      fileName: String(body.fileName || "珍贵回忆").slice(0, 120),
      byteSize,
      previewBase64: String(body.previewBase64 || "").slice(0, 512 * 1024),
      createdAt: nowIso()
    };
    const upload = await app.uploadFile({
      cloudPath: `album/${item.id}${fileExtension(item.fileName)}`,
      fileContent
    });
    if (!upload.fileID) throw new Error(upload.message || "上传文件失败");
    await put(collections.albumItems, item.id, { ...item, fileId: upload.fileID });
    return sendJson(response, {
      item: publicAlbumItem(item),
      quota: { usedBytes: usedBytes + byteSize, limitBytes: ALBUM_QUOTA_BYTES }
    }, 201);
  }

  const albumMatch = pathName.match(/^album\/([^/]+)$/);
  if (albumMatch && request.method === "GET") {
    const item = await get(collections.albumItems, albumMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    const file = await app.downloadFile({ fileID: item.fileId });
    if (!file.fileContent) throw new Error(file.message || "下载文件失败");
    return sendJson(response, { item: { ...publicAlbumItem(item), dataBase64: file.fileContent.toString("base64") } });
  }

  const albumPreviewMatch = pathName.match(/^album\/([^/]+)\/preview$/);
  if (albumPreviewMatch && request.method === "GET") {
    const item = await get(collections.albumItems, albumPreviewMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    return sendJson(response, { item: publicAlbumItem(item) });
  }

  if (albumPreviewMatch && request.method === "PUT") {
    const item = await get(collections.albumItems, albumPreviewMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    if (item.mediaType !== "image") return sendJson(response, { error: "invalid_media", message: "视频不能写入照片预览" }, 400);
    const body = await readJson(request);
    const previewBase64 = requiredString(body.previewBase64, "预览图");
    if (previewBase64.length > 512 * 1024) {
      return sendJson(response, { error: "preview_too_large", message: "预览图过大" }, 413);
    }
    await put(collections.albumItems, item.id, { ...item, previewBase64 });
    return sendJson(response, { ok: true });
  }

  if (albumMatch && request.method === "DELETE") {
    const item = await get(collections.albumItems, albumMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    await app.deleteFile({ fileList: [item.fileId] });
    await collections.albumItems.doc(item.id).remove();
    return sendJson(response, { ok: true });
  }

  const albumRenameMatch = pathName.match(/^album\/([^/]+)\/name$/);
  if (albumRenameMatch && request.method === "PUT") {
    const item = await get(collections.albumItems, albumRenameMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    const body = await readJson(request);
    const renamed = { ...item, fileName: albumNameWithOriginalExtension(requiredString(body.name, "名字"), item.fileName) };
    await put(collections.albumItems, item.id, renamed);
    return sendJson(response, { item: publicAlbumItem(renamed) });
  }

  if (request.method === "POST" && pathName === "location/update") {
    const body = await readJson(request);
    const latitude = Number(body.latitude);
    const longitude = Number(body.longitude);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      return sendJson(response, { error: "invalid_location" }, 400);
    }
    await put(collections.locations, user.id, {
      id: user.id,
      userId: user.id,
      latitude: coarse(latitude),
      longitude: coarse(longitude),
      updatedAt: nowIso()
    });
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "location/distance") {
    const mine = await get(collections.locations, user.id);
    const other = await get(collections.locations, otherUserId(user.id));
    if (!mine || !other) return sendJson(response, { available: false });
    return sendJson(response, { available: true, kilometers: roundedKm(distanceKm(mine, other)) });
  }

  return sendJson(response, { error: "not_found" }, 404);
}

async function ensureSeedData() {
  if (!(await get(collections.personas, DEFAULT_PERSONA_ID))) {
    const timestamp = nowIso();
    await put(collections.personas, DEFAULT_PERSONA_ID, { ...DEFAULT_PERSONA, createdAt: timestamp, updatedAt: timestamp });
  }
  await Promise.all(DEFAULT_FEATURES.map(async (feature) => {
    if (!(await get(collections.features, feature.id))) await put(collections.features, feature.id, feature);
  }));
}

async function createUserMessage(session, user, body, makeSimpleTitle = true) {
  const text = requiredString(body.text, "消息内容");
  const timestamp = nowIso();
  const message = {
    id: randomId(),
    sessionId: session.id,
    role: "user",
    senderId: user.id,
    text,
    createdAt: timestamp
  };
  await put(collections.messages, message.id, message);
  const nextTitle = makeSimpleTitle && session.title === DEFAULT_SESSION_TITLE ? titleFrom(text) : session.title;
  session.title = nextTitle;
  session.updatedAt = timestamp;
  await put(collections.sessions, session.id, session);
  return publicMessage(message);
}

async function createAssistantReply(sessionId, personaId) {
  const persona = await get(collections.personas, personaId) || DEFAULT_PERSONA;
  const history = await messagesForSession(sessionId, 20);
  if (!process.env.LLM_API_KEY) return fallbackReply();

  try {
    const response = await fetch(`${llmBaseUrl()}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${process.env.LLM_API_KEY}`
      },
      body: JSON.stringify({
        model: process.env.LLM_MODEL || "deepseek-v4-flash",
        messages: modelMessages(persona, history),
        temperature: 0.8
      })
    });
    if (!response.ok) {
      console.error("LLM request failed", response.status, await response.text());
      return fallbackReply();
    }
    const result = await response.json();
    return result?.choices?.[0]?.message?.content?.trim() || fallbackReply();
  } catch (error) {
    console.error("LLM request failed", error);
    return fallbackReply();
  }
}

async function streamFromModel(session, send) {
  const persona = await get(collections.personas, session.personaId) || DEFAULT_PERSONA;
  const history = await messagesForSession(session.id, 20);
  const response = await fetch(`${llmBaseUrl()}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${process.env.LLM_API_KEY}`
    },
    body: JSON.stringify({
      model: process.env.LLM_MODEL || "deepseek-v4-flash",
      messages: modelMessages(persona, history),
      temperature: 0.8,
      stream: true
    })
  });
  if (!response.ok || !response.body) {
    console.error("LLM stream failed", response.status, await response.text());
    return "";
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

async function saveAssistantMessage(session, text) {
  const message = {
    id: randomId(),
    sessionId: session.id,
    role: "assistant",
    senderId: "bot",
    text: text.trim() || fallbackReply(),
    createdAt: nowIso()
  };
  await put(collections.messages, message.id, message);
  await put(collections.sessions, session.id, { ...session, updatedAt: message.createdAt });
  return publicMessage(message);
}

async function streamAssistantReply(response, session, userMessage) {
  response.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    ...corsHeaders()
  });
  const send = (event, data) => response.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  let assistantText = "";
  try {
    send("user", { message: userMessage });
    if (process.env.LLM_API_KEY) {
      assistantText = await streamFromModel(session, send);
    }
    if (!assistantText) {
      assistantText = fallbackReply();
      send("chunk", { text: assistantText });
    }
  } catch (error) {
    console.error("LLM stream failed", error);
    assistantText = fallbackReply();
    send("chunk", { text: assistantText });
  }

  const assistantMessage = {
    id: randomId(),
    sessionId: session.id,
    role: "assistant",
    senderId: "bot",
    text: assistantText.trim() || fallbackReply(),
    createdAt: nowIso()
  };
  await put(collections.messages, assistantMessage.id, assistantMessage);
  const title = session.title === DEFAULT_SESSION_TITLE
    ? await createSessionTitle(userMessage.text, assistantMessage.text)
    : session.title;
  await put(collections.sessions, session.id, { ...session, title, updatedAt: assistantMessage.createdAt });
  send("done", { message: publicMessage(assistantMessage), title });
  response.end();
}

async function createSessionTitle(userText, assistantText) {
  if (!process.env.LLM_API_KEY) return titleFrom(userText);
  try {
    const response = await fetch(`${llmBaseUrl()}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${process.env.LLM_API_KEY}`
      },
      body: JSON.stringify({
        model: process.env.LLM_MODEL || "deepseek-v4-flash",
        messages: [
          { role: "system", content: "请根据这段对话生成一个简短中文标题，不超过12个字，只输出标题本身。" },
          { role: "user", content: `用户：${userText}\n助手：${assistantText}` }
        ],
        temperature: 0.4
      })
    });
    if (!response.ok) return titleFrom(userText);
    const result = await response.json();
    return cleanTitle(result?.choices?.[0]?.message?.content) || titleFrom(userText);
  } catch {
    return titleFrom(userText);
  }
}

function llmBaseUrl() {
  return (process.env.LLM_BASE_URL || "https://api.deepseek.com").replace(/\/$/, "");
}

function modelMessages(persona, history) {
  return [
    { role: "system", content: assistantSystemPrompt(persona) },
    ...history.map((message) => ({
      role: message.role === "assistant" ? "assistant" : "user",
      content: `${displayName(message.senderId)}：${message.text}`
    }))
  ];
}

async function messagesForSession(sessionId, limit = null) {
  const result = await collections.messages.where({ sessionId }).orderBy("createdAt", limit ? "desc" : "asc").limit(limit || 1000).get();
  const messages = (result.data || []).map(publicMessage);
  return limit ? messages.reverse() : messages;
}

async function get(collection, id) {
  const result = await collection.doc(id).get();
  return result.data?.[0] || null;
}

async function put(collection, id, data) {
  await collection.doc(id).set({ ...withoutDatabaseId(data), id });
}

async function query(collection, where = null, orderField = null, orderDirection = "asc") {
  let target = where ? collection.where(where) : collection;
  if (orderField) target = target.orderBy(orderField, orderDirection);
  const result = await target.limit(1000).get();
  return result.data || [];
}

function withoutDatabaseId(data) {
  const { _id, ...result } = data;
  return result;
}

function publicFeature(item) {
  return { id: item.id, title: item.title, status: item.status };
}

function publicPersona(item) {
  return { id: item.id, name: item.name, description: item.description, memory: item.memory || "", bubbleColor: item.bubbleColor || DEFAULT_BOT_BUBBLE_COLOR };
}

function publicSession(item) {
  return { id: item.id, title: item.title, personaId: item.personaId, createdBy: item.createdBy, createdAt: item.createdAt, updatedAt: item.updatedAt };
}

function publicMessage(item) {
  return { id: item.id, sessionId: item.sessionId, role: item.role, senderId: item.senderId, text: item.text, createdAt: item.createdAt };
}

function publicNote(item) {
  return { id: item.id, authorId: item.authorId, text: item.text, createdAt: item.createdAt, updatedAt: item.updatedAt, readAt: item.readAt || null };
}

function publicCalendarEvent(item) {
  return { id: item.id, date: item.date, title: item.title, note: item.note, createdBy: item.createdBy, createdAt: item.createdAt, updatedAt: item.updatedAt };
}

function publicAlbumItem(item) {
  return {
    id: item.id,
    uploaderId: item.uploaderId,
    mediaType: item.mediaType,
    mimeType: item.mimeType,
    fileName: item.fileName,
    byteSize: item.byteSize,
    previewBase64: item.previewBase64 || "",
    createdAt: item.createdAt
  };
}

function requireUser(request) {
  const auth = request.headers.authorization || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  return users().find((candidate) => candidate.token === token);
}

function users() {
  return [
    { id: "hkf", name: "锋宝", token: process.env.APP_TOKEN_HKF || "hkf-local-token", code: process.env.LOGIN_CODE_HKF || "hkf" },
    { id: "cl", name: "璐宝", token: process.env.APP_TOKEN_CL || "cl-local-token", code: process.env.LOGIN_CODE_CL || "cl" }
  ];
}

async function readJson(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_REQUEST_BYTES) throw new Error("请求内容太大");
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return text ? JSON.parse(text) : {};
}

function sendJson(response, body, status = 200) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    ...corsHeaders()
  });
  response.end(JSON.stringify(body));
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS"
  };
}

function randomId() {
  return crypto.randomUUID();
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
  return text.trim().replace(/\s+/g, " ").slice(0, 18) || DEFAULT_SESSION_TITLE;
}

function cleanTitle(text) {
  return String(text || "").replace(/[《》"'“”]/g, "").trim().slice(0, 18);
}

function assistantSystemPrompt(persona) {
  return [
    persona.description,
    `长期记忆：${persona.memory || "暂无"}`,
    "称呼规则：内部账号 hkf、cl、HKF、CL 只用于系统识别，回复时绝对不要输出这些账号或缩写。提到两位用户时，只称呼为“恺锋”和“小璐”。"
  ].join("\n");
}

function fallbackReply() {
  return "我在这里陪着你们。先慢慢说，不用一下子把所有情绪都整理好；能把感受说出来，本身就已经是在靠近彼此了。";
}

function normalizeColor(value) {
  const text = String(value || "").trim();
  return /^#[0-9A-Fa-f]{6}$/.test(text) ? text.toUpperCase() : DEFAULT_BOT_BUBBLE_COLOR;
}

function albumNameWithOriginalExtension(name, originalFileName) {
  const cleanName = name.replace(/[\\/:*?"<>|]/g, "").trim().slice(0, 80);
  const originalExtension = fileExtension(originalFileName);
  const base = cleanName.replace(/\.[^.]+$/, "") || "珍贵回忆";
  return `${base}${originalExtension}`;
}

function fileExtension(fileName) {
  const match = String(fileName || "").match(/(\.[A-Za-z0-9]{1,12})$/);
  return match ? match[1] : "";
}

function nextMonth(month) {
  if (!/^\d{4}-\d{2}$/.test(month)) return "9999-12-31";
  const [year, rawMonth] = month.split("-").map(Number);
  const date = new Date(Date.UTC(year, rawMonth, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-01`;
}

function albumUsedBytes(items) {
  return items.reduce((total, item) => total + Number(item.byteSize || 0), 0);
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
  if (senderId === "hkf") return "恺锋";
  if (senderId === "cl") return "小璐";
  return senderId;
}

function coarse(value) {
  return Math.round(value * 100) / 100;
}

function roundedKm(value) {
  return value < 10 ? Math.round(value * 10) / 10 : Math.round(value);
}

function distanceKm(a, b) {
  const earthRadius = 6371;
  const dLat = toRad(b.latitude - a.latitude);
  const dLon = toRad(b.longitude - a.longitude);
  const lat1 = toRad(a.latitude);
  const lat2 = toRad(b.latitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.sqrt(h));
}

function toRad(value) {
  return (value * Math.PI) / 180;
}

function nowIso() {
  return new Date().toISOString();
}
