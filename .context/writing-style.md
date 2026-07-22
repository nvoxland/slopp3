# Writing style — the docs site and the blog

Applies to `docs/` (the MkDocs site: guides, reference, philosophy, blog) and
other reader-facing prose we ship: release notes, README copy, the skills'
user-facing phrasing.

`.context/` docs and code comments are exempt — they're for us, and they're
allowed to be dense.

Blog posts skew personal and casual; docs skew terse and reference-style. Both
share the voice rules and the AI-tropes section.

## Voice

- **First person singular ("I")** for personal work, opinions, decisions.
  **"We"** for project-level statements. Second person for instructions.
- **Developer-to-developer.** A technical forum post, not a press release.
- **Plainspoken.** No buzzwords, no hype adjectives. Say what the thing does.
- **Comfortable with imperfection.** Admit missed milestones, known issues,
  half-baked ideas. "Not yet production ready" is fine.
- **Honest about uncertainty.** "I'm not sure this is the right approach"
  beats false confidence. An n=1 measurement gets labelled as one.

## Structure by page type

Release announcements: one- or two-sentence intro (what version, what's
notable), then a bulleted list of changes. A bug-fix-only release gets 2–3
sentences total — don't pad it.

Guides and reference: lead with the task or the concept, not setup
throat-clearing. Show the call before explaining it. Cross-link instead of
restating.

Philosophy pages: get to the argument in the first paragraph, and include the
cost. A page that only lists upsides reads as marketing.

## Do

- Get to the point immediately.
- Bulleted lists for features and changes.
- Name the tool, the flag, the result key. Specificity is the whole value.
- Admit what isn't done: "I was hoping to add X but wanted to get this out."
- Self-deprecating humour when it lands.

## Don't

- Marketing tone, superlatives ("amazing", "excited to announce").
- Pad a short announcement into a long post.
- Corporate speak.
- Over-polish. It should read like it was written quickly and honestly.
- "Stay tuned" or other empty closings.
- Sections added just to make a page look longer.

## Slopp-specific accuracy rules

The docs describe an enforcing system, so a wrong detail is worse here than a
vague one.

- **Name real tools with real argument shapes.** `help` and the MCP tool
  descriptions on a running server are the source of truth; the SKILL.md tool
  index is second. If they disagree, the server wins — and fix the skill.
- **Don't invent result keys.** `:status`, `:affected`, `:implicated`,
  `:red-first`, `:carried-errors` are real; a plausible-sounding one is a lie a
  reader will act on.
- **Watch retired vocabulary.** `^:isolated` is now `^:external`;
  `:reads`/`:effects` are legacy spellings of `:internal`/`:external`; the
  form-level `^:reads` marker is still valid. Grep before a sweep.
- **The site is derived.** A rule belongs in the skill first. When the site and
  a skill disagree, the skill is right and the site is stale.

## Avoiding AI tropes

LLM-assisted prose has a recognisable shape: false drama, hedged grandiosity,
recycled scaffolding. One trope occasionally is fine; clusters, or one pattern
repeated, give the game away. Vary, be specific, accept some imperfection. If a
sentence could open any blog post on any product, rewrite it.

Source: <https://tropes.fyi/tropes-md>.

### Word choice
- No magic adverbs: "quietly", "deeply", "fundamentally", "remarkably",
  "arguably".
- Banned vocabulary: "delve", "utilize", "leverage" (verb), "robust",
  "streamline", "harness", "seamless", "certainly".
- No grand nouns as decoration: "tapestry", "landscape", "paradigm", "synergy",
  "ecosystem", "framework". Name the thing.
- Plain copulas. "X is Y", not "X serves as Y" / "stands as" / "represents".

### Sentence structure
- No negative parallelism: "It's not X — it's Y", "not because X but because
  Y", "The question isn't X. The question is Y."
- No dramatic countdowns: "Not X. Not Y. Just Z."
- No self-answered rhetorical questions: "The result? Devastating."
- Don't open multiple sentences identically.
- Rule of three sparingly. Stacked tricolons read as AI.
- Cut filler transitions: "It's worth noting", "Importantly", "Notably".
- Cut hollow `-ing` tails: "highlighting its importance".
- Avoid fake "from X to Y" ranges when X and Y aren't on a real spectrum.

### Paragraph and list structure
- Don't manufacture emphasis with one-sentence paragraphs.
- If it's a list, format it as a list. Don't disguise enumeration as prose with
  "The first… The second…".

### Tone
- No false suspense: "Here's the kicker", "Here's the thing".
- No patronising analogies unless the analogy is load-bearing.
- No "imagine a world where…" futurism.
- No performative vulnerability.
- Don't assert something is "clear", "simple", or "obvious" without showing it.
- Don't inflate stakes. Most features are not world-historical.
- Cut "Let's break this down" / "Let's unpack" / "Let's explore".
- Name sources. Not "experts argue".
- Don't coin compound labels without defining and earning them.

### Formatting
- Em dashes: a few per piece, not twenty. When in doubt use `--` or a comma.
- Don't bold-prefix every bullet. Bold when one item genuinely stands out.
- Straight quotes and ASCII arrows (`->`), not smart quotes or `→`.

### Composition
- Don't announce structure ("In this section we'll explore…") or recap it
  ("As we've seen…").
- Introduce a metaphor once.
- Don't restate one idea ten different ways.
- Never duplicate paragraphs. Reread long pieces.
- No signposted conclusions: "In conclusion", "To sum up".
- No "despite its challenges, X remains promising" closer.

## Editing pass

After drafting, search for: `delve`, `tapestry`, `landscape`, `leverage`,
`serves as`, `worth noting`, `Imagine`, `In conclusion`, `Despite`, `seamless`,
`excited`, `—`, `→`. Each hit is a question, not necessarily a fix — but each
one earns its place or gets cut.
