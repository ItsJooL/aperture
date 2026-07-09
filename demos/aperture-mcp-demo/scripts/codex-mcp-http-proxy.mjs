#!/usr/bin/env node

const endpoint = process.env.APERTURE_MCP_URL || "http://localhost:8082/mcp";
const apiKey = process.env.APERTURE_API_KEY;

if (!apiKey) {
  console.error("APERTURE_API_KEY is required for the Aperture MCP demo proxy.");
  process.exit(1);
}

let buffer = Buffer.alloc(0);

process.stdin.on("data", chunk => {
  buffer = Buffer.concat([buffer, chunk]);
  drain().catch(error => {
    console.error(error?.stack || String(error));
    process.exit(1);
  });
});

async function drain() {
  while (true) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd === -1) return;

    const header = buffer.subarray(0, headerEnd).toString("utf8");
    const match = /^Content-Length:\s*(\d+)$/im.exec(header);
    if (!match) {
      throw new Error("Missing Content-Length header from MCP client.");
    }

    const contentLength = Number(match[1]);
    const frameEnd = headerEnd + 4 + contentLength;
    if (buffer.length < frameEnd) return;

    const body = buffer.subarray(headerEnd + 4, frameEnd).toString("utf8");
    buffer = buffer.subarray(frameEnd);

    const response = await forward(body);
    if (response !== undefined) {
      writeFrame(JSON.stringify(response));
    }
  }
}

async function forward(body) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "X-API-Key": apiKey,
      "Content-Type": "application/json",
      "Accept": "application/json, text/event-stream"
    },
    body
  });

  const text = await response.text();
  if (!response.ok) {
    return errorResponse(body, response.status, text);
  }
  if (text.trim() === "") {
    return undefined;
  }

  return parseResponse(text);
}

function parseResponse(text) {
  const trimmed = text.trim();
  if (trimmed.startsWith("data:")) {
    const data = trimmed
      .split(/\r?\n/)
      .filter(line => line.startsWith("data:"))
      .map(line => line.slice(5).trim())
      .join("\n");
    return JSON.parse(data);
  }
  return JSON.parse(trimmed);
}

function errorResponse(body, status, text) {
  let id = null;
  try {
    id = JSON.parse(body).id ?? null;
  } catch {
    // Keep JSON-RPC id null when the original frame was not parseable.
  }
  return {
    jsonrpc: "2.0",
    id,
    error: {
      code: -32000,
      message: `Aperture MCP HTTP request failed with ${status}`,
      data: text
    }
  };
}

function writeFrame(payload) {
  const bytes = Buffer.byteLength(payload, "utf8");
  process.stdout.write(`Content-Length: ${bytes}\r\n\r\n${payload}`);
}
