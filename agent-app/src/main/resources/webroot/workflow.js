// ── Bootstrap: resolve pipeline config UI link + load scenarios ──────
fetch('/workflow/api/config')
  .then(r => r.json())
  .then(cfg => {
    const port = cfg.pipelineUiPort || 8081;
    const link = document.getElementById('pipelineConfigLink');
    link.href = window.location.protocol + '//' + window.location.hostname + ':' + port + '/pipeline/';
  })
  .catch(() => { /* leave href=#, link simply won't navigate */ });

fetch('/workflow/scenarios.json')
  .then(r => r.json())
  .then(data => { scenarios = data; })
  .catch(() => { /* keep empty object; buttons will be no-ops */ });

// ── State ────────────────────────────────────────────────────────
let running = false;
let eventSource = null;
let runCounter = 0;

let scenarios = {}; // populated at runtime from /workflow/scenarios.json

// ── Scenario popup ───────────────────────────────────────────────
function buildPayload(key) {
  const s = scenarios[key];
  if (!s) return {};
  const p = {};
  if (s.tradeId)       p.tradeId       = s.tradeId;
  if (s.reason)        p.reason        = s.reason;
  if (s.counterparty)  p.counterparty  = s.counterparty;
  if (s.instrument)    p.instrument    = s.instrument;
  if (s.notional)      p.notional      = s.notional;
  if (s.currency)      p.currency      = s.currency;
  if (s.settlementDate) p.settlementDate = s.settlementDate;
  if (s.book)          p.book          = s.book;
  if (s.altIds)        p.altIds        = s.altIds;
  return p;
}

function toggleScenarioMenu(e, key) {
  e.stopPropagation();
  const popup = document.getElementById('scenarioPopup');
  const btn = e.currentTarget;
  const row = btn.closest('.scenario-row');
  const s = scenarios[key];
  const label = row.querySelector('.scenario-label').textContent;

  // position popup below the row
  const rect = row.getBoundingClientRect();
  const panelRect = row.closest('.control-panel').getBoundingClientRect();
  popup.style.top  = (rect.bottom - panelRect.top + row.closest('.control-panel').scrollTop + 4) + 'px';
  popup.style.left = '0';
  popup.style.right = '0';

  if (popup.style.display !== 'none' && popup.dataset.key === key) {
    closeScenarioPopup(); return;
  }
  popup.dataset.key = key;

  const payload = buildPayload(key);
  const json = JSON.stringify(payload, null, 2);
  const curl = 'curl -s -X POST http://localhost:8080/trade/failures \\\n'
             + '  -H \'content-type: application/json\' \\\n'
             + '  -d \'' + JSON.stringify(payload) + "'";

  document.getElementById('scenarioPopupTitle').textContent = label.replace(/^[^\w]+/, '').trim();
  document.getElementById('scenarioPopupCode').textContent = curl + '\n\n# Payload\n' + json;
  popup.style.display = 'block';
}

function closeScenarioPopup() {
  document.getElementById('scenarioPopup').style.display = 'none';
}

function copyScenarioPopup() {
  const text = document.getElementById('scenarioPopupCode').textContent;
  navigator.clipboard.writeText(text).then(() => {
    const btn = document.querySelector('.scenario-popup-copy');
    btn.textContent = 'Copied!';
    setTimeout(() => btn.textContent = 'Copy', 1500);
  });
}

// close popup when clicking outside
document.addEventListener('click', e => {
  const popup = document.getElementById('scenarioPopup');
  if (!popup.contains(e.target) && !e.target.classList.contains('scenario-menu-btn')) {
    closeScenarioPopup();
  }
});

// ── Scenario loader ──────────────────────────────────────────────
function loadScenario(key) {
  const s = scenarios[key];
  if (!s) return;
  document.getElementById('tradeId').value = s.tradeId;
  document.getElementById('reason').value = s.reason;
  document.getElementById('counterparty').value = s.counterparty || '';
  document.getElementById('instrument').value = s.instrument || '';
  document.getElementById('notional').value = s.notional || '';
  document.getElementById('currency').value = s.currency || '';
  document.getElementById('settlementDate').value = s.settlementDate || '';
  document.getElementById('book').value = s.book || '';
  document.getElementById('altIds').value = s.altIds || '';
  // highlight the clicked button
  document.querySelectorAll('.scenario-btn').forEach(b => b.style.borderColor = '');
  event.currentTarget.style.borderColor = 'var(--accent)';
}

// ── View Trade XML ──────────────────────────────────────────────
async function viewTradeXml() {
  const tradeId = document.getElementById('tradeId').value.trim();
  if (!tradeId) { alert('Enter a Trade ID first'); return; }
  try {
    const res = await fetch('/workflow/api/trade/' + encodeURIComponent(tradeId));
    if (!res.ok) {
      alert('No trade XML found for ' + tradeId);
      return;
    }
    const xml = await res.text();
    document.getElementById('xmlTitle').textContent = 'Trade XML \u2014 ' + tradeId;
    document.getElementById('xmlContent').innerHTML = highlightXml(xml);
    document.getElementById('xmlOverlay').classList.add('visible');
  } catch (e) {
    alert('Error fetching trade XML: ' + e.message);
  }
}

function highlightXml(xml) {
  // tokenise then highlight — avoids regex passes clobbering each other
  const esc = c => c.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const CLR_TAG  = '#58a6ff'; // cyan  — tag names
  const CLR_ATTR = '#7ee787'; // green — attribute names
  const CLR_VAL  = '#f0883e'; // orange — attribute values
  const CLR_CMT  = '#8b949e'; // grey  — comments
  const CLR_HDR  = '#8b949e'; // grey  — <?xml ...?>

  let out = '';
  let i = 0;
  while (i < xml.length) {
    // XML comment
    if (xml.startsWith('<!--', i)) {
      const end = xml.indexOf('-->', i);
      const cmtEnd = end < 0 ? xml.length : end + 3;
      out += '<span style="color:' + CLR_CMT + '">' + esc(xml.substring(i, cmtEnd)) + '</span>';
      i = cmtEnd; continue;
    }
    // Processing instruction <?...?>
    if (xml.startsWith('<?', i)) {
      const end = xml.indexOf('?>', i);
      const piEnd = end < 0 ? xml.length : end + 2;
      out += '<span style="color:' + CLR_HDR + '">' + esc(xml.substring(i, piEnd)) + '</span>';
      i = piEnd; continue;
    }
    // Tag (opening, closing, self-closing)
    if (xml[i] === '<') {
      const end = xml.indexOf('>', i);
      if (end < 0) { out += esc(xml.substring(i)); break; }
      const raw = xml.substring(i, end + 1);
      // parse tag: name + attributes
      const m = raw.match(/^<(\/?)([\w:\-]+)([\s\S]*?)(\/?)>$/);
      if (m) {
        const slash1 = m[1], tagName = m[2], attrStr = m[3], slash2 = m[4];
        out += '&lt;' + esc(slash1)
          + '<span style="color:' + CLR_TAG + '">' + esc(tagName) + '</span>';
        // highlight attributes
        if (attrStr) {
          out += attrStr.replace(/([\w:\-]+)\s*=\s*"([^"]*)"/g, function(_, name, val) {
            return ' <span style="color:' + CLR_ATTR + '">' + esc(name) + '</span>'
              + '=<span style="color:' + CLR_VAL + '">&quot;' + esc(val) + '&quot;</span>';
          }).replace(/([\w:\-]+)\s*=\s*'([^']*)'/g, function(_, name, val) {
            return ' <span style="color:' + CLR_ATTR + '">' + esc(name) + '</span>'
              + '=<span style="color:' + CLR_VAL + '">&#39;' + esc(val) + '&#39;</span>';
          });
        }
        out += esc(slash2) + '&gt;';
      } else {
        out += esc(raw);
      }
      i = end + 1; continue;
    }
    // Text content
    const next = xml.indexOf('<', i);
    const textEnd = next < 0 ? xml.length : next;
    out += esc(xml.substring(i, textEnd));
    i = textEnd;
  }
  return out;
}

// ── Reset ────────────────────────────────────────────────────────
function resetAll() {
  if (eventSource) { eventSource.close(); eventSource = null; }
  running = false;
  document.getElementById('runBtn').disabled = false;
  document.getElementById('runBtn').textContent = '▶ Run Workflow';
  document.getElementById('runBtn').classList.remove('running');
  document.getElementById('summaryBar').style.display = 'none';
  const rs = document.getElementById('result-steps');
  if (rs) { rs.innerHTML = ''; rs.style.display = 'none'; }
  // reset stages
  document.querySelectorAll('.stage-node').forEach(n => {
    n.className = 'stage-node idle';
    const det = n.querySelector('.stage-detail');
    if (det) det.textContent = det.dataset.default || det.textContent;
  });
  document.querySelectorAll('.flow-arrow').forEach(a => a.className = 'flow-arrow');
  document.querySelectorAll('.branch-label').forEach(l => l.className = 'branch-label');
  document.getElementById('agent-sub-steps').innerHTML = '';
  // save defaults
  saveDefaults();
  // reset event log
  document.getElementById('eventLog').innerHTML =
    '<div style="color:var(--muted);font-size:12px;padding:20px 0;text-align:center;">' +
    'Select a scenario and click <b>Run Workflow</b> to begin</div>';
}

function saveDefaults() {
  document.querySelectorAll('.stage-detail').forEach(d => {
    if (!d.dataset.default) d.dataset.default = d.textContent;
  });
}
saveDefaults();

// ── Stage updates ────────────────────────────────────────────────
function setStage(id, state, detail) {
  const node = document.getElementById('stage-' + id);
  if (!node) return;
  node.className = 'stage-node ' + state;
  if (detail) {
    const det = node.querySelector('.stage-detail');
    if (det) det.textContent = detail;
  }
}

function setArrow(id, state) {
  const arrow = document.getElementById('arrow-' + id);
  if (arrow) arrow.className = 'flow-arrow ' + state;
}

function setBranchLabel(branch, state) {
  const label = document.getElementById('label-' + branch);
  if (label) label.className = 'branch-label ' + state;
}

function addAgentSubStep(title, state, reasoning) {
  const container = document.getElementById('agent-sub-steps');
  const arrow = document.createElement('div');
  arrow.className = 'flow-arrow ' + (state === 'done' ? 'done' : 'active');
  arrow.style.height = '18px';
  container.appendChild(arrow);

  const step = document.createElement('div');
  step.className = 'sub-step ' + state;
  const titleDiv = document.createElement('div');
  titleDiv.className = 'sub-step-title';
  titleDiv.textContent = title;
  step.appendChild(titleDiv);
  if (reasoning) {
    const reasonDiv = document.createElement('div');
    reasonDiv.className = 'sub-step-reasoning';
    reasonDiv.textContent = '"' + reasoning + '"';
    step.appendChild(reasonDiv);
  }
  container.appendChild(step);
  return step;
}

function startIterationGroup(stepLabel) {
  const container = document.getElementById('agent-sub-steps');
  const group = document.createElement('div');
  group.className = 'iteration-group';
  group.id = 'iter-group-' + stepLabel;
  const label = document.createElement('div');
  label.className = 'iteration-label';
  label.textContent = stepLabel;
  group.appendChild(label);
  container.appendChild(group);
  return group;
}

function addSubStepToGroup(group, title, state, reasoning) {
  const arrow = document.createElement('div');
  arrow.className = 'flow-arrow ' + (state === 'done' ? 'done' : 'active');
  arrow.style.height = '14px';
  group.appendChild(arrow);

  const step = document.createElement('div');
  step.className = 'sub-step ' + state;
  const titleDiv = document.createElement('div');
  titleDiv.className = 'sub-step-title';
  titleDiv.textContent = title;
  step.appendChild(titleDiv);
  if (reasoning) {
    const reasonDiv = document.createElement('div');
    reasonDiv.className = 'sub-step-reasoning';
    reasonDiv.textContent = '"' + reasoning + '"';
    step.appendChild(reasonDiv);
  }
  group.appendChild(step);
  return step;
}

// ── Event log ────────────────────────────────────────────────────
function logEvent(stage, title, detail, cssClass) {
  const log = document.getElementById('eventLog');
  // remove placeholder
  if (log.querySelector('div[style]')) log.innerHTML = '';

  const entry = document.createElement('div');
  entry.className = 'event-entry stage-' + (cssClass || stage);

  const time = new Date().toLocaleTimeString('en-GB', { hour12: false, fractionalSecondDigits: 3 });
  entry.innerHTML =
    '<span class="event-time">' + time + '</span>' +
    '<div class="event-stage">' + escapeHtml(title) + '</div>' +
    (detail ? '<div class="event-data">' + escapeHtml(detail) + '</div>' : '');
  log.appendChild(entry);
  entry.scrollIntoView({ behavior: 'smooth', block: 'end' });
}

/** Log a rich reasoning entry with a blockquote and action arrow */
function logReasoning(stepLabel, reasoning, toolName) {
  const log = document.getElementById('eventLog');
  if (log.querySelector('div[style]')) log.innerHTML = '';

  const entry = document.createElement('div');
  entry.className = 'event-entry stage-reasoning';
  const time = new Date().toLocaleTimeString('en-GB', { hour12: false, fractionalSecondDigits: 3 });
  entry.innerHTML =
    '<span class="event-time">' + time + '</span>' +
    '<div class="event-stage">🧠 ' + escapeHtml(stepLabel) + ' — LLM Reasoning</div>' +
    '<div class="reasoning-quote">' + escapeHtml(reasoning || 'Deciding next action…') + '</div>' +
    '<div class="reasoning-action"><span class="action-arrow">→</span> Calls <b>' + escapeHtml(toolName) + '</b></div>';
  log.appendChild(entry);
  entry.scrollIntoView({ behavior: 'smooth', block: 'end' });
}

/** Log a rich tool result entry with key/value summary and expandable JSON */
function logToolResult(toolName, toolIcon, toolResult) {
  const log = document.getElementById('eventLog');
  if (log.querySelector('div[style]')) log.innerHTML = '';

  const entry = document.createElement('div');
  entry.className = 'event-entry stage-tool';
  const time = new Date().toLocaleTimeString('en-GB', { hour12: false, fractionalSecondDigits: 3 });

  // Build key-value summary from top-level fields
  let summaryHtml = '';
  const skipKeys = new Set(['status']);
  const summaryKeys = Object.keys(toolResult).filter(k => !skipKeys.has(k) && typeof toolResult[k] !== 'object').slice(0, 4);
  for (const key of summaryKeys) {
    summaryHtml += '<div class="tool-kv"><span class="tool-key">' + escapeHtml(key) + ':</span><span class="tool-val">' + escapeHtml(String(toolResult[key])) + '</span></div>';
  }

  const detailId = 'detail-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
  entry.innerHTML =
    '<span class="event-time">' + time + '</span>' +
    '<div class="event-stage">' + toolIcon + ' ' + escapeHtml(toolName) + ' → ' + escapeHtml(toolResult.status || 'ok') + '</div>' +
    (summaryHtml ? '<div class="tool-summary">' + summaryHtml + '</div>' : '') +
    '<button class="tool-detail-toggle" onclick="document.getElementById(\'' + detailId + '\').classList.toggle(\'expanded\')">show full result</button>' +
    '<div class="tool-detail-json" id="' + detailId + '">' + escapeHtml(JSON.stringify(toolResult, null, 2)) + '</div>';
  log.appendChild(entry);
  entry.scrollIntoView({ behavior: 'smooth', block: 'end' });
}

function escapeHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// ── Run workflow ─────────────────────────────────────────────────
async function runWorkflow() {
  if (running) return;
  running = true;
  runCounter++;
  const runId = runCounter;

  const btn = document.getElementById('runBtn');
  btn.disabled = true;
  btn.textContent = '⏳ Running…';
  btn.classList.add('running');

  // reset diagram
  document.querySelectorAll('.stage-node').forEach(n => n.className = 'stage-node idle');
  document.querySelectorAll('.flow-arrow').forEach(a => a.className = 'flow-arrow');
  document.querySelectorAll('.branch-label').forEach(l => l.className = 'branch-label');
  document.getElementById('agent-sub-steps').innerHTML = '';
  document.getElementById('summaryBar').style.display = 'none';
  document.getElementById('eventLog').innerHTML = '';

  const tradeId = document.getElementById('tradeId').value.trim() || 'T-100';
  const reason = document.getElementById('reason').value.trim() || 'Missing ISIN';
  const counterparty = document.getElementById('counterparty').value.trim();
  const instrument = document.getElementById('instrument').value.trim();
  const notional = document.getElementById('notional').value.trim();
  const currency = document.getElementById('currency').value.trim();
  const settlementDate = document.getElementById('settlementDate').value.trim();
  const book = document.getElementById('book').value.trim();
  const altIds = document.getElementById('altIds').value.trim();

  const payload = { tradeId, reason };
  if (counterparty) payload.counterparty = counterparty;
  if (instrument) payload.instrument = instrument;
  if (notional) payload.notional = notional;
  if (currency) payload.currency = currency;
  if (settlementDate) payload.settlementDate = settlementDate;
  if (book) payload.book = book;
  if (altIds) payload.altIds = altIds;

  // ── Step 1: HTTP send ──────────────────────────────────────
  logEvent('http', 'HTTP Request', 'POST /trade/failures\n' + JSON.stringify(payload, null, 2));
  setStage('http', 'active', 'Sending POST…');
  setArrow('1', 'active');
  await sleep(400);

  // ── Connect SSE for domain events ──────────────────────────
  if (eventSource) eventSource.close();
  eventSource = new EventSource('/workflow/api/events?tradeId=' + encodeURIComponent(tradeId));
  eventSource.addEventListener('domain-event', (e) => {
    try {
      const evt = JSON.parse(e.data);
      logEvent('event', '📤 ' + (evt.type || 'Event'), JSON.stringify(evt, null, 2), 'event');
      setStage('eventsink', 'done', evt.type || 'received');
      setArrow('4', 'done');
    } catch (err) { /* ignore */ }
  });
  eventSource.addEventListener('stage', (e) => {
    try {
      const stage = JSON.parse(e.data);
      handleStageEvent(stage);
    } catch (err) { /* ignore */ }
  });

  // ── Step 2: Send to API ────────────────────────────────────
  setStage('http', 'done', 'POST sent');
  setStage('bus', 'active', 'trade.failures');
  setArrow('1', 'done');
  logEvent('bus', 'Event Bus Dispatch', 'address: trade.failures', 'bus');
  await sleep(300);

  setStage('bus', 'done');
  setArrow('2', 'done');
  setStage('processor', 'active', 'Routing…');
  logEvent('processor', 'Failure Processor', 'Evaluating reason: "' + reason + '"', 'processor');

  try {
    const resp = await fetch('/workflow/api/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const result = await resp.json();

    if (runId !== runCounter) return; // stale run

    // ── Animate based on path ─────────────────────────────────
    const path = result.path || 'unknown';
    setStage('processor', 'done', 'Routed → ' + path);
    setArrow('3', 'done');

    if (path === 'deterministic') {
      await animateDeterministic(result, reason);
    } else if (path === 'agent') {
      await animateAgent(result);
    }

    // ── Final result ──────────────────────────────────────────
    setArrow('5', 'done');
    setStage('result', 'done', result.status === 'ok' ? 'Success' : 'Error');
    logEvent('result', '✅ Workflow Complete',
      'Path: ' + path + '\n' + JSON.stringify(result, null, 2), 'result');

    // summary bar + results card
    showSummary(path, result);
    showResultsCard(path, result, tradeId);

  } catch (err) {
    setStage('processor', 'error', 'Error');
    setStage('result', 'error', err.message);
    logEvent('error', '❌ Error', err.message, 'error');
  } finally {
    // close SSE after a short delay to catch late events
    setTimeout(() => { if (eventSource) { eventSource.close(); eventSource = null; } }, 2000);
    running = false;
    btn.disabled = false;
    btn.textContent = '▶ Run Workflow';
    btn.classList.remove('running');
  }
}

// ── Deterministic path animation ─────────────────────────────────
async function animateDeterministic(result, reason) {
  setBranchLabel('deterministic', 'active');
  const handlerType = reason === 'Missing ISIN' ? 'lookup-enrich' : 'escalate';
  setStage('handler', 'active', handlerType);
  logEvent('processor', '🔧 Deterministic Handler', 'type: ' + handlerType + '\nreason: ' + reason, 'processor');
  await sleep(600);

  setStage('handler', 'done', handlerType);
  setBranchLabel('deterministic', 'done');
  setStage('agent', 'skipped');
  setBranchLabel('agent', '');

  const resultEvent = result.resultEvent;
  if (resultEvent) {
    logEvent('event', '📤 ' + (resultEvent.type || 'Event'), JSON.stringify(resultEvent, null, 2), 'event');
    setStage('eventsink', 'done', resultEvent.type);
    setArrow('4', 'done');
  }
}

// ── Agent path animation ─────────────────────────────────────────
async function animateAgent(result) {
  setBranchLabel('agent', 'active');
  setStage('handler', 'skipped');
  setBranchLabel('deterministic', '');
  setStage('agent', 'active', 'Multi-step reasoning…');
  logEvent('agent', '🤖 Agent Runner',
    'Forwarded to agent.required — beginning multi-step reasoning chain', 'agent');
  await sleep(400);

  const trail = result.trail || [];
  const totalSteps = trail.length || 1;

  for (let i = 0; i < trail.length; i++) {
    const entry = trail[i];
    const cmd = entry.command || {};
    const toolResult = entry.toolResult || {};
    const tool = cmd.tool || 'unknown';
    const reasoning = cmd.reasoning || '';
    const stop = cmd.stop;
    const stepLabel = 'Step ' + (i + 1) + '/' + totalSteps;

    const toolIcon = tool.startsWith('data.') ? '🔎'
      : tool.startsWith('case.classify') ? '🏷️'
      : tool.startsWith('case.raise') ? '🎫'
      : tool.startsWith('comms.') ? '📣'
      : '🔧';

    // Create a visual iteration group in the flow diagram
    const group = startIterationGroup(stepLabel);

    // ── Reasoning phase ──────────────────────────────────
    setStage('agent', 'active', stepLabel + ': Thinking…');
    addSubStepToGroup(group, '🧠 Reasoning', 'active', reasoning);
    logReasoning(stepLabel, reasoning, tool);
    await sleep(600);

    // Mark reasoning done
    group.querySelectorAll('.sub-step').forEach(s => s.className = 'sub-step done');

    // ── Tool execution phase ─────────────────────────────
    setStage('agent', 'active', stepLabel + ': ' + tool);
    addSubStepToGroup(group, toolIcon + ' ' + tool, 'active');
    logToolResult(tool, toolIcon, toolResult);
    await sleep(500);

    // Mark tool done, mark iteration group done
    group.querySelectorAll('.sub-step').forEach(s => s.className = 'sub-step done');
    group.querySelectorAll('.flow-arrow').forEach(a => {
      if (!a.className.includes('done')) a.className += ' done';
    });
    group.classList.add('done');

    // ── Continue / Stop indicator ────────────────────────
    if (!stop && i < trail.length - 1) {
      logEvent('agent', '⏩ ' + stepLabel + ' complete — iterating',
        'stop=false — agent feeds context forward and re-enters the reasoning loop', 'continue');
      await sleep(250);
    }
  }

  // If no trail, fall back to simple animation
  if (trail.length === 0) {
    addAgentSubStep('🧠 LLM Decision', 'done');
    addAgentSubStep('🔧 Tool Call', 'done');
  }

  // Finalize
  document.querySelectorAll('#agent-sub-steps .sub-step').forEach(s => s.className = 'sub-step done');
  document.querySelectorAll('#agent-sub-steps .flow-arrow').forEach(a => {
    if (!a.className.includes('done')) a.className += ' done';
  });
  setStage('agent', 'done', totalSteps + ' step' + (totalSteps > 1 ? 's' : '') + ' complete');
  setBranchLabel('agent', 'done');
  setArrow('4', 'done');
  const lastResult = trail.length > 0 ? trail[trail.length - 1].toolResult : result.result;
  setStage('eventsink', 'done', lastResult?.type || lastResult?.status || 'done');
}

// ── Handle stage SSE events ──────────────────────────────────────
function handleStageEvent(stage) {
  const name = stage.stage || '';
  const detail = stage.detail || '';
  if (name === 'received' || name === 'dispatching') {
    logEvent('bus', '📡 ' + name, detail, 'bus');
  } else if (name === 'completed') {
    logEvent('result', '✅ Pipeline ' + name, detail, 'result');
  } else if (name === 'error') {
    logEvent('error', '❌ Pipeline error', detail, 'error');
  }
}

function showSummary(path, result) {
  const bar = document.getElementById('summaryBar');
  bar.style.display = 'flex';
  document.getElementById('summaryText').textContent =
    'Completed in ' + (result.resultEvent?.type || result.result?.status || 'ok');
  const badge = document.getElementById('summaryPath');
  badge.textContent = path;
  badge.className = 'path-badge path-' + path;
}

// ── Results Card ─────────────────────────────────────────────
// ── Generic tool-result detail extractor ─────────────────────────
// Works for any tool — no hardcoded tool names.
// Keys in SKIP are never shown (internal IDs / metadata).
// Keys in UNWRAP are descended into without adding their name as a prefix
// (so "data.counterparty" becomes just "counterparty", "event.type" → "type").
function buildToolDetails(toolResult) {
  const SKIP   = new Set(['correlationId', 'caseId', 'tradeId', 'status']);
  const UNWRAP = new Set(['data', 'event']);
  const trunc  = (s, n) => { const t = String(s); return t.length > n ? t.slice(0, n - 1) + '\u2026' : t; };

  // Compact "k: v · k: v" string from an object's primitive fields
  function primLine(obj, max) {
    return Object.entries(obj)
      .filter(([, v]) => v !== null && v !== undefined && typeof v !== 'object')
      .slice(0, max)
      .map(([k, v]) => k + ': ' + trunc(String(v), 70))
      .join('  \u00b7  ');
  }

  const lines = [];

  function walk(obj, prefix, depth) {
    if (!obj || typeof obj !== 'object' || depth > 2) return;
    for (const [k, v] of Object.entries(obj)) {
      if (SKIP.has(k) || v === null || v === undefined) continue;
      const label = prefix ? prefix + '.' + k : k;

      if (Array.isArray(v)) {
        if (!v.length) continue;
        const first = v[0];
        const compact = (typeof first === 'object' && first)
          ? primLine(first, 5)
          : v.slice(0, 5).map(String).join(', ');
        if (compact) lines.push(label + ' [' + v.length + ']: ' + trunc(compact, 200));

      } else if (typeof v === 'object') {
        // Unwrap transparent container keys (data, event) at depth 0
        if (UNWRAP.has(k) && depth === 0) {
          walk(v, '', depth + 1);
        } else {
          const pl = primLine(v, 7);
          if (pl) lines.push(label + ': ' + trunc(pl, 200));
          // One more level of recursion for nested objects
          if (depth < 1) walk(v, label, depth + 1);
        }

      } else {
        lines.push(label + ': ' + trunc(String(v), 200));
      }
    }
  }

  walk(toolResult, '', 0);
  return lines.slice(0, 14);
}

function showResultsCard(path, result, tradeId) {
  const stepsEl = document.getElementById('result-steps');
  if (!stepsEl) return;
  const trail = result.trail || [];

  const toolIcon = t =>
    t.startsWith('data.')   || t.startsWith('data_')   ? '🔎' :
    t.startsWith('case.cl') || t.startsWith('case_cl') ? '🏷️' :
    t.startsWith('case.ra') || t.startsWith('case_ra') ? '🎫' :
    t.startsWith('comms.')  || t.startsWith('comms_')  ? '📣' :
    t.startsWith('events.') || t.startsWith('events_') ? '📤' : '🔧';

  let stepsHtml = '';
  if (path === 'agent' && trail.length > 0) {
    for (let i = 0; i < trail.length; i++) {
      const entry      = trail[i];
      const cmd        = entry.command || {};
      const tool       = cmd.tool || 'unknown';
      const reasoning  = cmd.reasoning || '';
      const toolResult = entry.toolResult || {};

      const details    = buildToolDetails(toolResult);
      const detailHtml = details
        .map(d => '<div class="result-step-detail">' + escapeHtml(d) + '</div>')
        .join('');

      stepsHtml +=
        '<div class="result-step">' +
          '<span class="result-step-num">' + (i + 1) + '</span>' +
          '<span class="result-step-icon">' + toolIcon(tool) + '</span>' +
          '<div class="result-step-body">' +
            '<div class="result-step-tool">' + escapeHtml(tool) + '</div>' +
            (reasoning  ? '<div class="result-step-reasoning">' + escapeHtml(reasoning) + '</div>' : '') +
            detailHtml +
          '</div>' +
          '<span class="result-step-ok">\u2713</span>' +
        '</div>';
    }
  } else if (path === 'deterministic') {
    const evtType = result.resultEvent?.type || result.result?.type || '';
    stepsHtml =
      '<div class="result-step">' +
        '<span class="result-step-num">1</span>' +
        '<span class="result-step-icon">🔧</span>' +
        '<div class="result-step-body">' +
          '<div class="result-step-tool">Deterministic Handler</div>' +
          (evtType ? '<div class="result-step-summary">' + escapeHtml(evtType) + '</div>' : '') +
        '</div>' +
        '<span class="result-step-ok">✓</span>' +
      '</div>';
  }

  stepsEl.innerHTML = stepsHtml;
  stepsEl.style.display = stepsHtml ? 'flex' : 'none';
}

function closeOverlay() {
  document.getElementById('responseOverlay').classList.remove('visible');
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// pre-select first scenario
loadScenario('missing-isin');
