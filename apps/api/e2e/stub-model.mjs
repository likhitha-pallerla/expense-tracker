// A stand-in for an OpenAI-compatible model, so the whole AI path can be driven
// without a key and without spending anything.
//
// It is scripted rather than clever: each request is matched by what the system
// prompt asks for, and the reply is whatever the current scenario says. That
// makes it possible to test the things that matter and cannot otherwise be
// reached -- a fabricated figure in a summary, a confidence below the gate, a
// refusal, a budget running out -- all of which are the failure modes, and none
// of which a real model produces on demand.

import { createServer } from 'node:http';

let scenario = 'default';
const seen = [];

const replies = {
  default: {
    'natural-entry': { amount: 640, direction: 'debit', merchant: 'Blue Tokai', description: 'coffee beans', account: null, date: null },
    'alert-fallback': { payment: true, amount: 2499, direction: 'debit', merchant: 'NETFLIX', last4: '4821', date: null, confidence: 0.95 },
    'insights-summary': { summary: 'You spent a fair amount this month.' },
  },
  fabricate: {
    'insights-summary': { summary: 'You spent 12500 this month, up 15% on last month.' },
  },
  lowConfidence: {
    'alert-fallback': { payment: true, amount: 2499, direction: 'debit', merchant: 'NETFLIX', last4: null, date: null, confidence: 0.2 },
  },
  notAPayment: {
    'alert-fallback': { payment: false },
  },
  badAmount: {
    'alert-fallback': { payment: true, amount: 'about 2500', direction: 'debit', merchant: 'NETFLIX', last4: null, date: null, confidence: 0.99 },
  },
  noDirection: {
    'alert-fallback': { payment: true, amount: 2499, direction: 'outgoing', merchant: 'NETFLIX', last4: null, date: null, confidence: 0.99 },
  },
};

function purposeOf(system) {
  if (system.includes('extract one payment from a short sentence')) return 'natural-entry';
  if (system.includes('bank or payment notification')) return 'alert-fallback';
  if (system.includes('short paragraph')) return 'insights-summary';
  return 'unknown';
}

const server = createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/control') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const parsed = JSON.parse(body);
      if (parsed.scenario) scenario = parsed.scenario;
      if (parsed.reset) seen.length = 0;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ scenario, calls: seen.length }));
    });
    return;
  }

  if (req.method === 'GET' && req.url === '/seen') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(seen));
    return;
  }

  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    let payload;
    try {
      payload = JSON.parse(body);
    } catch {
      res.writeHead(400).end('{}');
      return;
    }

    const system = payload.messages?.find((m) => m.role === 'system')?.content ?? '';
    const user = payload.messages?.find((m) => m.role === 'user')?.content ?? '';
    const purpose = purposeOf(system);

    // Recorded so the test can assert on what actually left the machine.
    seen.push({ purpose, user, temperature: payload.temperature, model: payload.model });

    const reply = replies[scenario]?.[purpose] ?? replies.default[purpose] ?? {};

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(
      JSON.stringify({
        choices: [{ message: { content: JSON.stringify(reply) } }],
        usage: { prompt_tokens: 120, completion_tokens: 40 },
      }),
    );
  });
});

server.listen(9099, () => console.log('stub model on 9099'));
