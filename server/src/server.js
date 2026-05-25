import http from "node:http";
import tcb from "@cloudbase/node-sdk";

const DEFAULT_PERSONA_ID = "emotional-support";
const DEFAULT_SESSION_TITLE = "新的聊天";
const ALBUM_QUOTA_BYTES = 200 * 1024 * 1024;
const MAX_REQUEST_BYTES = 280 * 1024 * 1024;
const DEFAULT_BOT_BUBBLE_COLOR = "#FFE0A8";
const PORT = Number(process.env.PORT || 9000);
const DEFAULT_LLM_TIMEOUT_MS = 60000;
const MAX_MEMORY_SOURCE_CHARS = 40000;

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
  locations: db.collection("locations"),
  authSessions: db.collection("auth_sessions"),
  memoryDocuments: db.collection("memory_documents"),
  aiMemories: db.collection("ai_memories"),
  chatRequests: db.collection("chat_requests")
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
    const timestamp = nowIso();
    const token = `${user.id}.${randomId()}`;
    await put(collections.authSessions, user.id, {
      id: user.id,
      userId: user.id,
      deviceId: requiredString(body.deviceId, "设备信息").slice(0, 120),
      token,
      createdAt: timestamp,
      updatedAt: timestamp
    });
    return sendJson(response, { token, user: publicUser(user) });
  }

  const user = await requireUser(request);
  if (!user) {
    const hasToken = String(request.headers.authorization || "").startsWith("Bearer ");
    return sendJson(response, {
      error: hasToken ? "session_replaced" : "unauthorized",
      message: hasToken ? "该身份已在另一台设备登录，请重新进入小世界" : "请先登录"
    }, 401);
  }

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
    let session = await get(collections.sessions, messagesMatch[1]);
    if (!session) return sendJson(response, { error: "session_not_found" }, 404);
    const body = await readJson(request);
    const duplicate = await existingChatRequest(body.requestId);
    if (duplicate) return sendJson(response, { error: "duplicate_request", message: "这条消息已经发送过了" }, 409);
    try {
      session = await acquireChatReplyLock(session.id);
    } catch (error) {
      if (error.code === "reply_in_progress") return sendJson(response, { error: error.code, message: error.message }, 409);
      throw error;
    }
    try {
      const userMessage = await createUserMessage(session, user, body);
      await recordChatRequest(body.requestId, session.id, userMessage.id);
      const assistantText = await createAssistantReply(session.id, session.personaId);
      const assistantMessage = await saveAssistantMessage(session, assistantText);
      void considerChatMemory(session.id).catch((error) => logLlmNetworkFailure("memory", error));
      return sendJson(response, { messages: [userMessage, assistantMessage] }, 201);
    } finally {
      await releaseChatReplyLock(session.id);
    }
  }

  const streamMatch = pathName.match(/^chat\/sessions\/([^/]+)\/messages\/stream$/);
  if (streamMatch && request.method === "POST") {
    let session = await get(collections.sessions, streamMatch[1]);
    if (!session) return sendJson(response, { error: "session_not_found", message: "没有找到这个聊天" }, 404);
    const body = await readJson(request);
    const duplicate = await existingChatRequest(body.requestId);
    if (duplicate) return sendJson(response, { error: "duplicate_request", message: "这条消息已经发送过了" }, 409);
    try {
      session = await acquireChatReplyLock(session.id);
    } catch (error) {
      if (error.code === "reply_in_progress") return sendJson(response, { error: error.code, message: error.message }, 409);
      throw error;
    }
    try {
      const userMessage = await createUserMessage(session, user, body, false);
      await recordChatRequest(body.requestId, session.id, userMessage.id);
      return await streamAssistantReply(response, session, userMessage);
    } catch (error) {
      await releaseChatReplyLock(session.id);
      throw error;
    }
  }

  if (request.method === "GET" && pathName === "notes") {
    const notes = await query(collections.notes, null, "createdAt", "desc");
    const unreadCount = notes.filter((note) => note.authorId !== user.id && !note.readAt).length;
    return sendJson(response, { notes: notes.map(publicNote), unreadCount });
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
      readAt: null,
      revision: 1
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
      updatedAt: timestamp,
      revision: 1
    };
    await put(collections.calendarEvents, event.id, event);
    return sendJson(response, { event: publicCalendarEvent(event) }, 201);
  }

  const calendarMatch = pathName.match(/^calendar\/events\/([^/]+)$/);
  if (calendarMatch && request.method === "PUT") {
    const current = await get(collections.calendarEvents, calendarMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这个日历事项" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicCalendarEvent(current));
    const event = {
      ...current,
      id: calendarMatch[1],
      date: requiredDate(body.date),
      title: requiredString(body.title, "日历标题").slice(0, 60),
      note: String(body.note || "").slice(0, 1000),
      updatedAt: nowIso(),
      revision: nextRevision(current)
    };
    await put(collections.calendarEvents, event.id, event);
    return sendJson(response, { event: publicCalendarEvent(event) });
  }

  if (calendarMatch && request.method === "DELETE") {
    const current = await get(collections.calendarEvents, calendarMatch[1]);
    if (!current) {
      return sendJson(response, { error: "not_found", message: "没有找到这个日历事项" }, 404);
    }
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicCalendarEvent(current));
    await collections.calendarEvents.doc(calendarMatch[1]).remove();
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "album") {
    const items = await query(collections.albumItems, null, "createdAt", "desc");
    return sendJson(response, {
      items: items.filter((item) => item.fileId).slice(0, 100).map(publicAlbumItem),
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
    const mimeType = String(body.mimeType || "application/octet-stream").slice(0, 120);
    const item = {
      id: randomId(),
      uploaderId: user.id,
      mediaType: mimeType.startsWith("video/") ? "video" : "image",
      mimeType,
      fileName: String(body.fileName || "珍贵回忆").slice(0, 120),
      byteSize,
      previewBase64: String(body.previewBase64 || "").slice(0, 512 * 1024),
      createdAt: nowIso(),
      revision: 1
    };
    let usedBytes = 0;
    try {
      await db.runTransaction(async (transaction) => {
        const result = await transaction.collection("album_items").limit(1000).get();
        usedBytes = albumUsedBytes(result.data || []);
        if (usedBytes + byteSize > ALBUM_QUOTA_BYTES) {
          const error = new Error("相册空间已经不够了，可以先删除一些旧照片或视频");
          error.code = "album_quota_exceeded";
          throw error;
        }
        await transaction.collection("album_items").doc(item.id).set({ ...item, id: item.id, uploadPending: true });
      });
    } catch (error) {
      if (error.code === "album_quota_exceeded") {
        return sendJson(response, { error: error.code, message: error.message }, 413);
      }
      throw error;
    }
    try {
      const upload = await app.uploadFile({
        cloudPath: `album/${item.id}${fileExtension(item.fileName)}`,
        fileContent
      });
      if (!upload.fileID) throw new Error(upload.message || "上传文件失败");
      await put(collections.albumItems, item.id, { ...item, fileId: upload.fileID });
    } catch (error) {
      await collections.albumItems.doc(item.id).remove();
      throw error;
    }
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
    const body = await readJson(request);
    if (!matchesRevision(item, body.revision)) return conflict(response, publicAlbumItem(item));
    await app.deleteFile({ fileList: [item.fileId] });
    await collections.albumItems.doc(item.id).remove();
    return sendJson(response, { ok: true });
  }

  const albumRenameMatch = pathName.match(/^album\/([^/]+)\/name$/);
  if (albumRenameMatch && request.method === "PUT") {
    const item = await get(collections.albumItems, albumRenameMatch[1]);
    if (!item) return sendJson(response, { error: "not_found", message: "没有找到这段回忆" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(item, body.revision)) return conflict(response, publicAlbumItem(item));
    const renamed = {
      ...item,
      fileName: albumNameWithOriginalExtension(requiredString(body.name, "名字"), item.fileName),
      revision: nextRevision(item)
    };
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
      province: String(body.province || "").slice(0, 40),
      city: String(body.city || "").slice(0, 40),
      updatedAt: nowIso()
    });
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "location/distance") {
    const mine = await get(collections.locations, user.id);
    const other = await get(collections.locations, otherUserId(user.id));
    if (!mine || !other) return sendJson(response, { available: false });
    return sendJson(response, {
      available: true,
      kilometers: roundedKm(distanceKm(mine, other)),
      mine: publicLocation(mine),
      other: publicLocation(other)
    });
  }

  if (request.method === "GET" && pathName === "memories/documents") {
    const documents = await query(collections.memoryDocuments, null, "updatedAt", "desc");
    return sendJson(response, { documents: documents.map(publicMemoryDocument) });
  }

  if (request.method === "POST" && pathName === "memories/documents") {
    const body = await readJson(request);
    const timestamp = nowIso();
    const document = {
      id: randomId(),
      title: requiredString(body.title, "标题").slice(0, 60),
      content: requiredString(body.content, "内容").slice(0, 5000),
      createdBy: user.id,
      createdAt: timestamp,
      updatedAt: timestamp,
      revision: 1
    };
    await put(collections.memoryDocuments, document.id, document);
    return sendJson(response, { document: publicMemoryDocument(document) }, 201);
  }

  const documentMatch = pathName.match(/^memories\/documents\/([^/]+)$/);
  if (documentMatch && request.method === "PUT") {
    const current = await get(collections.memoryDocuments, documentMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这篇回忆" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicMemoryDocument(current));
    const document = {
      ...current,
      title: requiredString(body.title, "标题").slice(0, 60),
      content: requiredString(body.content, "内容").slice(0, 5000),
      updatedAt: nowIso(),
      revision: nextRevision(current)
    };
    await put(collections.memoryDocuments, document.id, document);
    return sendJson(response, { document: publicMemoryDocument(document) });
  }

  if (documentMatch && request.method === "DELETE") {
    const current = await get(collections.memoryDocuments, documentMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这篇回忆" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicMemoryDocument(current));
    await collections.memoryDocuments.doc(current.id).remove();
    return sendJson(response, { ok: true });
  }

  if (request.method === "GET" && pathName === "memories/ai") {
    const memories = await query(collections.aiMemories, null, "updatedAt", "desc");
    return sendJson(response, { memories: memories.map(publicAiMemory) });
  }

  const aiMemoryMatch = pathName.match(/^memories\/ai\/([^/]+)$/);
  if (aiMemoryMatch && request.method === "PUT") {
    const current = await get(collections.aiMemories, aiMemoryMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这条记忆" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicAiMemory(current));
    const memory = {
      ...current,
      content: requiredString(body.content, "记忆内容").slice(0, 1000),
      editedByUser: true,
      updatedAt: nowIso(),
      revision: nextRevision(current)
    };
    await put(collections.aiMemories, memory.id, memory);
    return sendJson(response, { memory: publicAiMemory(memory) });
  }

  if (aiMemoryMatch && request.method === "DELETE") {
    const current = await get(collections.aiMemories, aiMemoryMatch[1]);
    if (!current) return sendJson(response, { error: "not_found", message: "没有找到这条记忆" }, 404);
    const body = await readJson(request);
    if (!matchesRevision(current, body.revision)) return conflict(response, publicAiMemory(current));
    await collections.aiMemories.doc(current.id).remove();
    return sendJson(response, { ok: true });
  }

  if (request.method === "POST" && pathName === "memories/ai/from-materials") {
    const saved = await generateMemoriesFromMaterials();
    return sendJson(response, { memories: saved.map(publicAiMemory), count: saved.length });
  }

  return sendJson(response, { error: "not_found" }, 404);
}

async function ensureSeedData() {
  if (!(await get(collections.personas, DEFAULT_PERSONA_ID))) {
    const timestamp = nowIso();
    await put(collections.personas, DEFAULT_PERSONA_ID, { ...DEFAULT_PERSONA, createdAt: timestamp, updatedAt: timestamp });
  }
  const personas = await query(collections.personas);
  await Promise.all(personas.filter((persona) => String(persona.memory || "").trim()).map(async (persona) => {
    const documentId = `legacy-persona-${persona.id}`;
    if (await get(collections.memoryDocuments, documentId)) return;
    const timestamp = nowIso();
    await put(collections.memoryDocuments, documentId, {
      id: documentId,
      title: `${persona.name}的旧版记忆`,
      content: String(persona.memory).slice(0, 5000),
      createdBy: "hkf",
      createdAt: timestamp,
      updatedAt: timestamp,
      revision: 1
    });
  }));
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
        model: llmModel(),
        messages: await modelMessages(persona, history),
        temperature: 0.8
      }),
      signal: llmRequestSignal()
    });
    if (!response.ok) {
      await logLlmHttpFailure("reply", response);
      return fallbackReply();
    }
    const result = await response.json();
    return result?.choices?.[0]?.message?.content?.trim() || fallbackReply();
  } catch (error) {
    logLlmNetworkFailure("reply", error);
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
      model: llmModel(),
      messages: await modelMessages(persona, history),
      temperature: 0.8,
      stream: true
    }),
    signal: llmRequestSignal()
  });
  if (!response.ok || !response.body) {
    await logLlmHttpFailure("stream", response);
    return "";
  }

  let assistantText = "";
  let openingText = "";
  let replyStarted = false;
  const appendChunk = (chunk) => {
    if (replyStarted) {
      assistantText += chunk;
      send("chunk", { text: chunk });
      return;
    }
    openingText += chunk;
    openingText = cleanAssistantText(openingText);
    if (!openingText || couldBecomeAssistantPrefix(openingText)) return;
    replyStarted = true;
    assistantText += openingText;
    send("chunk", { text: openingText });
    openingText = "";
  };
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
          appendChunk(chunk);
        }
      } catch {
        // Ignore malformed provider stream fragments.
      }
    }
  }
  if (openingText) {
    const remaining = cleanAssistantText(openingText);
    assistantText += remaining;
    if (remaining) send("chunk", { text: remaining });
  }
  return assistantText;
}

async function saveAssistantMessage(session, text) {
  const cleanText = cleanAssistantText(text);
  const message = {
    id: randomId(),
    sessionId: session.id,
    role: "assistant",
    senderId: "bot",
    text: cleanText || fallbackReply(),
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
      assistantText = cleanAssistantText(await streamFromModel(session, send));
    }
    if (!assistantText) {
      assistantText = fallbackReply();
      send("chunk", { text: assistantText });
    }
  } catch (error) {
    logLlmNetworkFailure("stream", error);
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
  await releaseChatReplyLock(session.id);
  void considerChatMemory(session.id).catch((error) => logLlmNetworkFailure("memory", error));
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
        model: llmModel(),
        messages: [
          { role: "system", content: "请根据这段对话生成一个简短中文标题，不超过12个字，只输出标题本身。" },
          { role: "user", content: `用户：${userText}\n助手：${assistantText}` }
        ],
        temperature: 0.4
      }),
      signal: llmRequestSignal()
    });
    if (!response.ok) {
      await logLlmHttpFailure("title", response);
      return titleFrom(userText);
    }
    const result = await response.json();
    return cleanTitle(result?.choices?.[0]?.message?.content) || titleFrom(userText);
  } catch (error) {
    logLlmNetworkFailure("title", error);
    return titleFrom(userText);
  }
}

function llmBaseUrl() {
  return (process.env.LLM_BASE_URL || "https://api.deepseek.com").replace(/\/$/, "");
}

function llmModel() {
  return process.env.LLM_MODEL || "deepseek-v4-flash";
}

function llmRequestSignal() {
  const configured = Number(process.env.LLM_TIMEOUT_MS);
  const timeoutMs = Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_LLM_TIMEOUT_MS;
  return AbortSignal.timeout(timeoutMs);
}

async function logLlmHttpFailure(operation, response) {
  const details = (await response.text()).slice(0, 500);
  console.error("LLM HTTP failure", {
    operation,
    baseUrl: llmBaseUrl(),
    model: llmModel(),
    status: response.status,
    details
  });
}

function logLlmNetworkFailure(operation, error) {
  const cause = error?.cause;
  console.error("LLM network failure", {
    operation,
    baseUrl: llmBaseUrl(),
    model: llmModel(),
    name: error?.name,
    message: error?.message,
    code: error?.code || cause?.code,
    cause: cause?.message
  });
}

async function modelMessages(persona, history) {
  const memories = await query(collections.aiMemories, null, "updatedAt", "desc");
  return [
    { role: "system", content: assistantSystemPrompt(persona, memories.slice(0, 30)) },
    ...history.map((message) => ({
      role: message.role === "assistant" ? "assistant" : "user",
      content: message.role === "assistant"
        ? cleanAssistantText(message.text)
        : `${displayName(message.senderId)}：${message.text}`
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
  const text = item.role === "assistant" ? cleanAssistantText(item.text) : item.text;
  return { id: item.id, sessionId: item.sessionId, role: item.role, senderId: item.senderId, text, createdAt: item.createdAt };
}

function publicNote(item) {
  return { id: item.id, authorId: item.authorId, text: item.text, createdAt: item.createdAt, updatedAt: item.updatedAt, readAt: item.readAt || null, revision: Number(item.revision || 1) };
}

function publicCalendarEvent(item) {
  return { id: item.id, date: item.date, title: item.title, note: item.note, createdBy: item.createdBy, createdAt: item.createdAt, updatedAt: item.updatedAt, revision: Number(item.revision || 1) };
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
    createdAt: item.createdAt,
    revision: Number(item.revision || 1)
  };
}

function publicMemoryDocument(item) {
  return {
    id: item.id,
    title: item.title,
    content: item.content,
    createdBy: item.createdBy,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    revision: Number(item.revision || 1)
  };
}

function publicAiMemory(item) {
  return {
    id: item.id,
    kind: item.kind,
    content: item.content,
    sourceType: item.sourceType || item.kind,
    sourceIds: item.sourceIds || [],
    generatedAt: item.generatedAt,
    updatedAt: item.updatedAt,
    editedByUser: Boolean(item.editedByUser),
    revision: Number(item.revision || 1)
  };
}

function publicLocation(item) {
  return {
    userId: item.userId,
    name: publicUser(users().find((user) => user.id === item.userId)).name,
    province: item.province || "",
    city: item.city || "",
    updatedAt: item.updatedAt
  };
}

async function requireUser(request) {
  const auth = request.headers.authorization || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!token) return null;
  const sessions = await query(collections.authSessions, { token });
  const session = sessions[0];
  return session ? users().find((candidate) => candidate.id === session.userId) || null : null;
}

function users() {
  return [
    { id: "hkf", name: "锋宝", code: process.env.LOGIN_CODE_HKF || "hkf" },
    { id: "cl", name: "璐宝", code: process.env.LOGIN_CODE_CL || "cl" }
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

function assistantSystemPrompt(persona, memories = []) {
  const savedMemories = memories.length
    ? memories.map((memory) => `- ${memory.content}`).join("\n")
    : "暂无";
  return [
    persona.description,
    "你的名字是小暖。",
    `长期记忆：\n${savedMemories}`,
    "称呼规则：内部账号 hkf、cl、HKF、CL 只用于系统识别，回复时绝对不要输出这些账号或缩写。提到两位用户时，只称呼为“恺锋”和“小璐”。",
    "回复正文不要以“小暖：”“机器人：”“助手：”或类似角色标签开头。"
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
  if (senderId === "bot") return "小暖";
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

function matchesRevision(item, revision) {
  return Number(revision || 1) === Number(item.revision || 1);
}

function nextRevision(item) {
  return Number(item.revision || 1) + 1;
}

function conflict(response, latest) {
  return sendJson(response, { error: "conflict", message: "内容已被另一台设备修改，请查看最新内容后再保存", latest }, 409);
}

function cleanAssistantText(value) {
  let text = String(value || "").trim();
  const prefix = /^(?:机器人|助手|小暖|小陪伴)\s*[：:]\s*/;
  while (prefix.test(text)) text = text.replace(prefix, "").trimStart();
  return text;
}

function couldBecomeAssistantPrefix(value) {
  const text = String(value || "");
  return ["机器人：", "机器人:", "助手：", "助手:", "小暖：", "小暖:", "小陪伴：", "小陪伴:"]
    .some((prefix) => prefix.startsWith(text));
}

async function existingChatRequest(requestId) {
  const id = String(requestId || "").trim();
  return id ? get(collections.chatRequests, id) : null;
}

async function recordChatRequest(requestId, sessionId, messageId) {
  const id = String(requestId || "").trim();
  if (!id) return;
  await put(collections.chatRequests, id, { id, sessionId, messageId, createdAt: nowIso() });
}

async function acquireChatReplyLock(sessionId) {
  return db.runTransaction(async (transaction) => {
    const reference = transaction.collection("sessions").doc(sessionId);
    const result = await reference.get();
    const session = result.data?.[0];
    if (!session) {
      const error = new Error("没有找到这个聊天");
      error.code = "session_not_found";
      throw error;
    }
    const lockedAt = session.replyLockedAt ? Date.parse(session.replyLockedAt) : 0;
    if (lockedAt && Date.now() - lockedAt < 120000) {
      const error = new Error("小暖正在回复上一条消息，请稍等一下");
      error.code = "reply_in_progress";
      throw error;
    }
    const locked = { ...withoutDatabaseId(session), id: sessionId, replyLockedAt: nowIso() };
    await reference.set(locked);
    return locked;
  });
}

async function releaseChatReplyLock(sessionId) {
  const session = await get(collections.sessions, sessionId);
  if (!session?.replyLockedAt) return;
  await put(collections.sessions, sessionId, { ...session, replyLockedAt: null });
}

async function considerChatMemory(sessionId) {
  if (!process.env.LLM_API_KEY) return [];
  const messages = await messagesForSession(sessionId);
  if (!messages.length) return [];
  const context = messages.map((message) => `${displayName(message.senderId)}：${message.text}`).join("\n").slice(-MAX_MEMORY_SOURCE_CHARS);
  const decision = await requestLlmText([
    {
      role: "system",
      content: "你负责判断情侣聊天中是否出现值得长期记住的信息。只有明确要求记住、稳定事实、重要共同经历、承诺或持续计划才回答 YES；普通闲聊、短暂情绪、一次性问题回答 NO。只输出 YES 或 NO。"
    },
    { role: "user", content: context }
  ], 0.1);
  if (!/^YES\b/i.test(decision)) return [];
  return persistGeneratedMemories("chat", "chat", [sessionId], context);
}

async function generateMemoriesFromMaterials() {
  const [documents, events, notes] = await Promise.all([
    query(collections.memoryDocuments, null, "updatedAt", "desc"),
    query(collections.calendarEvents, null, "date", "desc"),
    query(collections.notes, null, "createdAt", "desc")
  ]);
  if (!documents.length && !events.length && !notes.length) return [];
  const source = [
    "【回忆文档】",
    ...documents.map((item) => `${item.title}：${item.content}`),
    "【重要日子】",
    ...events.map((item) => `${item.date} ${item.title} ${item.note || ""}`),
    "【留言】",
    ...notes.map((item) => `${displayName(item.authorId)}：${item.text}`)
  ].join("\n").slice(0, MAX_MEMORY_SOURCE_CHARS);
  if (!source.trim()) return [];
  return persistGeneratedMemories(
    "life-material",
    "document,calendar,note",
    [...documents.map((item) => item.id), ...events.map((item) => item.id), ...notes.map((item) => item.id)],
    source
  );
}

async function persistGeneratedMemories(kind, sourceType, sourceIds, sourceText) {
  let contents = [];
  if (process.env.LLM_API_KEY) {
    const raw = await requestLlmText([
      {
        role: "system",
        content: "从情侣的共同资料中提炼适合长期保存的原子记忆。不要保存短暂情绪、原话长引用、推测、账号或精确位置。只输出 JSON 字符串数组，每项是一条简短中文记忆，最多 6 条。"
      },
      { role: "user", content: sourceText }
    ], 0.3);
    contents = parseMemoryArray(raw);
  }
  if (!contents.length) {
    const fallback = sourceText.replace(/\s+/g, " ").slice(0, 180);
    if (fallback) contents = [`共同资料摘要：${fallback}`];
  }
  const current = await query(collections.aiMemories, { kind });
  const generated = [];
  for (const content of contents.slice(0, 6)) {
    const duplicate = current.find((item) => item.content === content);
    if (duplicate) continue;
    const timestamp = nowIso();
    const memory = {
      id: randomId(),
      kind,
      content: String(content).slice(0, 1000),
      sourceType,
      sourceIds: sourceIds.slice(0, 100),
      generatedAt: timestamp,
      updatedAt: timestamp,
      editedByUser: false,
      revision: 1
    };
    await put(collections.aiMemories, memory.id, memory);
    generated.push(memory);
  }
  return generated;
}

async function requestLlmText(messages, temperature) {
  const response = await fetch(`${llmBaseUrl()}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${process.env.LLM_API_KEY}` },
    body: JSON.stringify({ model: llmModel(), messages, temperature }),
    signal: llmRequestSignal()
  });
  if (!response.ok) {
    await logLlmHttpFailure("memory", response);
    return "";
  }
  const result = await response.json();
  return String(result?.choices?.[0]?.message?.content || "").trim();
}

function parseMemoryArray(raw) {
  try {
    const jsonText = String(raw || "").replace(/^```(?:json)?\s*|\s*```$/g, "");
    const parsed = JSON.parse(jsonText);
    return Array.isArray(parsed) ? parsed.map((value) => String(value).trim()).filter(Boolean) : [];
  } catch {
    return [];
  }
}
