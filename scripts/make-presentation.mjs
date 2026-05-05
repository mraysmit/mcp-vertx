import PptxGenJS from 'pptxgenjs';

const pptx = new PptxGenJS();

// ── Colour palette ──────────────────────────────────────────────────────────
const DARK_BLUE   = '1B3A6B';
const MID_BLUE    = '2E6DB4';
const ACCENT      = 'F5A623';   // amber
const WHITE       = 'FFFFFF';
const LIGHT_GREY  = 'F4F6FA';
const BODY_TEXT   = '2C2C2C';

// ── Fonts ───────────────────────────────────────────────────────────────────
const FONT_TITLE  = 'Calibri';
const FONT_BODY   = 'Calibri';

// ── Slide dimensions (widescreen 13.33 × 7.5 in) ───────────────────────────
pptx.layout = 'LAYOUT_WIDE';

// ════════════════════════════════════════════════════════════════════════════
// Helper – coloured background rectangle
// ════════════════════════════════════════════════════════════════════════════
function addBg(slide, fill) {
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 0, w: '100%', h: '100%',
    fill: { color: fill }, line: { color: fill },
  });
}

// Helper – section header bar at top
function addTopBar(slide) {
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 0, w: '100%', h: 1.25,
    fill: { color: DARK_BLUE }, line: { color: DARK_BLUE },
  });
}

// Helper – accent bottom stripe
function addBottomStripe(slide) {
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 7.1, w: '100%', h: 0.4,
    fill: { color: ACCENT }, line: { color: ACCENT },
  });
}

// Helper – icon-style circle bullet
function addCircle(slide, x, y, label) {
  slide.addShape(pptx.ShapeType.ellipse, {
    x, y, w: 0.55, h: 0.55,
    fill: { color: ACCENT }, line: { color: ACCENT },
  });
  slide.addText(label, {
    x, y, w: 0.55, h: 0.55,
    align: 'center', valign: 'middle',
    fontSize: 14, bold: true, color: DARK_BLUE,
    fontFace: FONT_BODY,
  });
}

// ════════════════════════════════════════════════════════════════════════════
// SLIDE 1 – Title / What is this?
// ════════════════════════════════════════════════════════════════════════════
{
  const slide = pptx.addSlide();

  // Background split: dark top half, light bottom half
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 0, w: '100%', h: 4.1,
    fill: { color: DARK_BLUE }, line: { color: DARK_BLUE },
  });
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 4.1, w: '100%', h: 3.4,
    fill: { color: LIGHT_GREY }, line: { color: LIGHT_GREY },
  });
  addBottomStripe(slide);

  // Amber accent bar under headline
  slide.addShape(pptx.ShapeType.rect, {
    x: 0.7, y: 2.65, w: 3.2, h: 0.12,
    fill: { color: ACCENT }, line: { color: ACCENT },
  });

  // Title
  slide.addText('Smart Trade-Failure\nAssistant', {
    x: 0.7, y: 0.55, w: 9.0, h: 1.9,
    fontSize: 40, bold: true, color: WHITE,
    fontFace: FONT_TITLE, valign: 'bottom',
  });

  // Sub-title
  slide.addText('Automatically diagnoses and resolves post-trade problems using a hybrid of deterministic rules and AI reasoning — so your team only sees the exceptions that truly need them.', {
    x: 0.7, y: 2.85, w: 11.5, h: 0.65,
    fontSize: 17, color: WHITE, fontFace: FONT_BODY,
  });

  // Hybrid principle pill
  slide.addShape(pptx.ShapeType.roundRect, {
    x: 0.7, y: 3.52, w: 11.9, h: 0.44,
    fill: { color: ACCENT, transparency: 15 }, line: { color: ACCENT, pt: 0 },
    rectRadius: 0.22,
  });
  slide.addText('⚙️  Deterministic where we can · Non-deterministic (AI) only where we must — predictable, auditable, and smart', {
    x: 0.85, y: 3.52, w: 11.6, h: 0.44,
    align: 'center', valign: 'middle',
    fontSize: 12, bold: true, color: DARK_BLUE, fontFace: FONT_BODY,
  });

  // Three "what it does" cards
  const cards = [
    { x: 0.55, icon: '⚙️', head: 'Deterministic first', body: 'Known failures follow fixed, proven rules — same input, same result, every time. Fast, cheap, and fully auditable.' },
    { x: 4.65, icon: '🤖', head: 'AI for the unknown', body: 'Non-deterministic AI reasoning kicks in only when no rule applies — handling novel, ambiguous situations a rules engine would miss or misroute.' },
    { x: 8.75, icon: '🔔', head: 'Right alert, right team', body: 'Routes notifications to Operations, Trading or Risk with context — not just a raw error code.' },
  ];

  for (const c of cards) {
    // Card background
    slide.addShape(pptx.ShapeType.roundRect, {
      x: c.x, y: 4.2, w: 3.8, h: 2.65,
      fill: { color: WHITE }, line: { color: MID_BLUE, pt: 1.5 },
      rectRadius: 0.15,
    });
    slide.addText(c.icon, {
      x: c.x + 0.15, y: 4.27, w: 0.7, h: 0.6,
      fontSize: 22, valign: 'middle',
    });
    slide.addText(c.head, {
      x: c.x + 0.15, y: 4.8, w: 3.5, h: 0.42,
      fontSize: 13, bold: true, color: DARK_BLUE, fontFace: FONT_BODY,
    });
    slide.addText(c.body, {
      x: c.x + 0.15, y: 5.23, w: 3.5, h: 1.4,
      fontSize: 11.5, color: BODY_TEXT, fontFace: FONT_BODY,
      wrap: true,
    });
  }

  // Footer
  slide.addText('vertx5-mcp  |  Confidential', {
    x: 0, y: 7.15, w: '100%', h: 0.3,
    align: 'center', fontSize: 9, color: WHITE, fontFace: FONT_BODY,
  });
}

// ════════════════════════════════════════════════════════════════════════════
// SLIDE 2 – Deterministic vs AI — why the hybrid matters
// ════════════════════════════════════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addBg(slide, LIGHT_GREY);
  addBottomStripe(slide);

  // Top header bar — split colour
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 0, w: '50%', h: 1.15,
    fill: { color: MID_BLUE }, line: { color: MID_BLUE },
  });
  slide.addShape(pptx.ShapeType.rect, {
    x: '50%', y: 0, w: '50%', h: 1.15,
    fill: { color: DARK_BLUE }, line: { color: DARK_BLUE },
  });

  // Column headings
  slide.addText('Deterministic Workflow', {
    x: 0.4, y: 0.15, w: 5.9, h: 0.75,
    fontSize: 20, bold: true, color: WHITE, fontFace: FONT_TITLE,
  });
  slide.addText('AI / LLM Workflow', {
    x: 6.8, y: 0.15, w: 6.0, h: 0.75,
    fontSize: 20, bold: true, color: WHITE, fontFace: FONT_TITLE,
  });

  // Dividing line
  slide.addShape(pptx.ShapeType.line, {
    x: 6.665, y: 0, w: 0, h: 7.5,
    line: { color: ACCENT, pt: 2.5 },
  });

  // ── Left column: DETERMINISTIC ──────────────────────────────────────────
  const detRows = [
    { icon: '📜', head: 'Follows fixed rules',       body: 'The same input always produces the same output, every single time. No surprises.' },
    { icon: '⚡', head: 'Instant and predictable',   body: 'Decisions are made in milliseconds from a lookup table or a documented procedure.' },
    { icon: '✅', head: 'Fully auditable',            body: 'Every step is traceable to a specific business rule — exactly what regulators and auditors want.' },
    { icon: '🚫', head: 'Cannot handle the unknown', body: 'If a situation was never anticipated when the rules were written, the workflow fails or misroutes.' },
  ];

  detRows.forEach((r, i) => {
    const ty = 1.35 + i * 1.38;
    slide.addShape(pptx.ShapeType.roundRect, {
      x: 0.35, y: ty, w: 6.0, h: 1.22,
      fill: { color: WHITE }, line: { color: MID_BLUE, pt: 1.2 },
      rectRadius: 0.12,
    });
    slide.addText(r.icon, {
      x: 0.52, y: ty + 0.1, w: 0.65, h: 0.65, fontSize: 19,
    });
    slide.addText(r.head, {
      x: 1.25, y: ty + 0.08, w: 4.9, h: 0.38,
      fontSize: 12.5, bold: true, color: DARK_BLUE, fontFace: FONT_BODY,
    });
    slide.addText(r.body, {
      x: 1.25, y: ty + 0.48, w: 4.9, h: 0.65,
      fontSize: 10.5, color: BODY_TEXT, fontFace: FONT_BODY, wrap: true,
    });
  });

  // ── Right column: AI / LLM ──────────────────────────────────────────────
  const llmRows = [
    { icon: '🧠', head: 'Reasons about anything',    body: 'Can handle novel, ambiguous, or complex situations that no pre-written rule could anticipate.' },
    { icon: '🔍', head: 'Context-aware',              body: 'Considers multiple signals together — regulation, history, counterparty, exposure — before deciding.' },
    { icon: '⚠️', head: 'Can be unpredictable',      body: 'The same input may occasionally produce different outputs. Answers can be plausible but wrong.' },
    { icon: '💸', head: 'Slower and more expensive', body: 'Every LLM call has a cost and latency. Using it for routine, well-understood tasks wastes both.' },
  ];

  llmRows.forEach((r, i) => {
    const ty = 1.35 + i * 1.38;
    slide.addShape(pptx.ShapeType.roundRect, {
      x: 6.98, y: ty, w: 6.0, h: 1.22,
      fill: { color: WHITE }, line: { color: DARK_BLUE, pt: 1.2 },
      rectRadius: 0.12,
    });
    slide.addText(r.icon, {
      x: 7.15, y: ty + 0.1, w: 0.65, h: 0.65, fontSize: 19,
    });
    slide.addText(r.head, {
      x: 7.88, y: ty + 0.08, w: 4.9, h: 0.38,
      fontSize: 12.5, bold: true, color: DARK_BLUE, fontFace: FONT_BODY,
    });
    slide.addText(r.body, {
      x: 7.88, y: ty + 0.48, w: 4.9, h: 0.65,
      fontSize: 10.5, color: BODY_TEXT, fontFace: FONT_BODY, wrap: true,
    });
  });

  // ── Callout banner across the bottom ────────────────────────────────────
  slide.addShape(pptx.ShapeType.rect, {
    x: 0, y: 6.7, w: '100%', h: 0.42,
    fill: { color: ACCENT }, line: { color: ACCENT },
  });
  slide.addText(
    '💡  Our system uses deterministic rules for everything it already knows — and only calls the AI when the situation is genuinely novel.',
    {
      x: 0.3, y: 6.7, w: 12.73, h: 0.42,
      align: 'center', fontSize: 11.5, bold: true, color: DARK_BLUE,
      fontFace: FONT_BODY, valign: 'middle',
    }
  );

  slide.addText('vertx5-mcp  |  Confidential', {
    x: 0, y: 7.15, w: '100%', h: 0.3,
    align: 'center', fontSize: 9, color: DARK_BLUE, fontFace: FONT_BODY,
  });
}

// ════════════════════════════════════════════════════════════════════════════
// SLIDE 3 (was 2) – How it works (process flow)
// ════════════════════════════════════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addBg(slide, LIGHT_GREY);
  addTopBar(slide);
  addBottomStripe(slide);

  // Header text
  slide.addText('How It Works', {
    x: 0.5, y: 0.2, w: 12, h: 0.9,
    fontSize: 28, bold: true, color: WHITE, fontFace: FONT_TITLE,
  });

  // Five-step flow — boxes with arrows between them
  const steps = [
    { n: '1', head: 'Trade Fails', body: 'A post-trade problem arrives (e.g. missing data, settlement mismatch, sanctions flag).' },
    { n: '2', head: 'Is it known?', body: 'A rule-based processor checks whether this type of failure has a standard fix.' },
    { n: '3', head: 'AI Analysis', body: 'If unusual, the AI agent gathers context, weighs evidence and decides the best course of action.' },
    { n: '4', head: 'Action Taken', body: 'The system raises a ticket, sends an alert or triggers a repair — automatically.' },
    { n: '5', head: 'Team Notified', body: 'The right people get the right message — with full context, not just a code.' },
  ];

  const BOX_W = 2.25;
  const BOX_H = 3.2;
  const ROW_Y = 1.7;
  const ARROW_W = 0.18;
  const GAP = (13.33 - (steps.length * BOX_W + (steps.length - 1) * ARROW_W)) / 2;

  steps.forEach((s, i) => {
    const bx = GAP + i * (BOX_W + ARROW_W);

    // Box shadow effect (slightly offset grey rect)
    slide.addShape(pptx.ShapeType.roundRect, {
      x: bx + 0.07, y: ROW_Y + 0.07, w: BOX_W, h: BOX_H,
      fill: { color: 'CCCCCC' }, line: { color: 'CCCCCC' },
      rectRadius: 0.18,
    });

    // Main box
    slide.addShape(pptx.ShapeType.roundRect, {
      x: bx, y: ROW_Y, w: BOX_W, h: BOX_H,
      fill: { color: i === 2 ? MID_BLUE : WHITE },
      line: { color: i === 2 ? MID_BLUE : MID_BLUE, pt: 1.5 },
      rectRadius: 0.18,
    });

    // Step number circle
    addCircle(slide, bx + BOX_W / 2 - 0.275, ROW_Y + 0.22, s.n);

    // Step heading
    slide.addText(s.head, {
      x: bx + 0.1, y: ROW_Y + 0.95, w: BOX_W - 0.2, h: 0.55,
      align: 'center', fontSize: 13, bold: true,
      color: i === 2 ? WHITE : DARK_BLUE, fontFace: FONT_BODY,
    });

    // Step body
    slide.addText(s.body, {
      x: bx + 0.12, y: ROW_Y + 1.55, w: BOX_W - 0.24, h: 1.5,
      align: 'center', fontSize: 10.5, wrap: true,
      color: i === 2 ? WHITE : BODY_TEXT, fontFace: FONT_BODY,
    });

    // Arrow between boxes
    if (i < steps.length - 1) {
      slide.addShape(pptx.ShapeType.rightArrow, {
        x: bx + BOX_W, y: ROW_Y + BOX_H / 2 - 0.22,
        w: ARROW_W + 0.06, h: 0.44,
        fill: { color: ACCENT }, line: { color: ACCENT },
      });
    }
  });

  // Bottom note
  slide.addText('Known failures resolve in milliseconds · Unknown failures are safely escalated via AI', {
    x: 0.5, y: 5.2, w: 12.3, h: 0.45,
    align: 'center', fontSize: 11, italic: true, color: DARK_BLUE, fontFace: FONT_BODY,
  });

  slide.addText('vertx5-mcp  |  Confidential', {
    x: 0, y: 7.15, w: '100%', h: 0.3,
    align: 'center', fontSize: 9, color: DARK_BLUE, fontFace: FONT_BODY,
  });
}

// ════════════════════════════════════════════════════════════════════════════
// SLIDE 4 (was 3) – Why it matters / Key benefits
// ════════════════════════════════════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addBg(slide, DARK_BLUE);
  addBottomStripe(slide);

  // Large decorative circle (top-right)
  slide.addShape(pptx.ShapeType.ellipse, {
    x: 10.5, y: -1.5, w: 5.5, h: 5.5,
    fill: { color: MID_BLUE, transparency: 70 }, line: { color: MID_BLUE, transparency: 70 },
  });

  // Header
  slide.addText('Why It Matters', {
    x: 0.7, y: 0.35, w: 9, h: 0.9,
    fontSize: 32, bold: true, color: WHITE, fontFace: FONT_TITLE,
  });

  // Sub-header amber underline
  slide.addShape(pptx.ShapeType.rect, {
    x: 0.7, y: 1.2, w: 2.8, h: 0.1,
    fill: { color: ACCENT }, line: { color: ACCENT },
  });

  // Six benefit tiles in a 2 × 3 grid
  const benefits = [
    { icon: '⏱️', head: 'Faster resolution',      body: 'Failures that once took hours to diagnose are handled automatically — day or night.' },
    { icon: '🎯', head: 'Fewer false alarms',      body: 'The AI assesses context before escalating, so your team only gets alerts that need human attention.' },
    { icon: '📋', head: 'Full audit trail',         body: 'Every decision — and its reasoning — is logged for compliance and review.' },
    { icon: '🔗', head: 'Handles complexity',       body: 'Multi-leg trade cascades, netting agreements and regulatory citations are understood, not just flagged.' },
    { icon: '🛡️', head: 'Regulatory awareness',    body: 'Cites specific rules (e.g. OFAC 31 CFR § 501.604) so compliance teams can act with confidence.' },
    { icon: '🔌', head: 'Easy integration',         body: 'Exposes a standard MCP interface — plugs into any AI toolchain or orchestration platform.' },
  ];

  const TILE_W = 5.8;
  const TILE_H = 1.65;
  const COL_X  = [0.5, 6.85];
  const ROW_YS = [1.55, 3.35, 5.15];

  benefits.forEach((b, i) => {
    const col = i % 2;
    const row = Math.floor(i / 2);
    const tx = COL_X[col];
    const ty = ROW_YS[row];

    // Tile background
    slide.addShape(pptx.ShapeType.roundRect, {
      x: tx, y: ty, w: TILE_W, h: TILE_H,
      fill: { color: '162D55' },
      line: { color: ACCENT, pt: 1 },
      rectRadius: 0.12,
    });

    // Icon
    slide.addText(b.icon, {
      x: tx + 0.18, y: ty + 0.15, w: 0.65, h: 0.65,
      fontSize: 20, valign: 'middle',
    });

    // Heading
    slide.addText(b.head, {
      x: tx + 0.9, y: ty + 0.12, w: TILE_W - 1.1, h: 0.45,
      fontSize: 13, bold: true, color: ACCENT, fontFace: FONT_BODY,
    });

    // Body
    slide.addText(b.body, {
      x: tx + 0.9, y: ty + 0.58, w: TILE_W - 1.1, h: 1.0,
      fontSize: 10.5, color: 'D6E4F7', fontFace: FONT_BODY, wrap: true,
    });
  });

  slide.addText('vertx5-mcp  |  Confidential', {
    x: 0, y: 7.15, w: '100%', h: 0.3,
    align: 'center', fontSize: 9, color: ACCENT, fontFace: FONT_BODY,
  });
}

// ════════════════════════════════════════════════════════════════════════════
// Write file
// ════════════════════════════════════════════════════════════════════════════
const outPath = 'vertx5-mcp-overview.pptx';
pptx.writeFile({ fileName: outPath }).then(() => {
  console.log(`Presentation saved → ${outPath}`);
});
