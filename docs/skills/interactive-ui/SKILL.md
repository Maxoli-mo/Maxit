---
name: interactive-ui
description: Render interactive kai-ui components in chat or as a complete screen. Archived for a future skills system; not loaded by Maxit.
---

# Interactive UI (parked)

Archived on 2026-10-21 from `ChatSystemPromptBuilder.kt`. This file is outside app resources and is not registered or injected into any prompt. The existing renderer remains available for old messages. Re-enabling generation is deferred to the future skills system.

The two modes below are mutually exclusive. Previously, Dynamic UI was injected in ordinary remote chat when its setting was enabled (default: on); Interactive UI replaced it for full-screen conversations. Both shared the exact component catalog below. The original Kotlin builder is also preserved in `.opencode/backups/interactive-ui-2026-10-21.tar.gz`.

## Shared component catalog

Format: wrap a JSON object in ```kai-ui fences.

Components: column, row, card, box, text, button, text_input, checkbox, switch, select, radio_group, slider, chip_group, table, list, divider, image, icon, code, progress, countdown, alert, tabs, accordion, quote, badge, stat, avatar.
- text: {"type":"text","value":"...","style":"headline|title|body|caption","bold":true,"color":"primary|secondary|error"} — do NOT use markdown formatting (**, *, #, etc.) in text values; use the bold/italic/style properties instead
- button: {"type":"button","label":"...","action":{...},"variant":"filled|outlined|text|tonal"}
- text_input: {"type":"text_input","id":"...","label":"...","placeholder":"...","value":"..."}
- checkbox: {"type":"checkbox","id":"...","label":"...","checked":false}
- switch: {"type":"switch","id":"...","label":"...","checked":false}
- select: {"type":"select","id":"...","label":"...","options":["A","B"],"selected":"A"}
- radio_group: {"type":"radio_group","id":"...","label":"...","options":["A","B"],"selected":"A"}
- slider: {"type":"slider","id":"...","label":"...","value":50,"min":0,"max":100,"step":10}
- chip_group: {"type":"chip_group","id":"...","chips":[{"label":"Tag","value":"tag"}],"selection":"single|multi|none"} — selection mode: "single" (default, one at a time), "multi" (any number), or "none" (display-only tags, no interaction, id not needed). For "single" and "multi" a button must collectFrom the chip_group id to send the selection.
- list: {"type":"list","items":[...],"ordered":false} — bullet (or numbered) list; the app renders bullets/numbers automatically, so do NOT include bullet characters (•, -, *) or numbering in item text
- table: {"type":"table","headers":["Col1","Col2"],"rows":[["a","b"]]} — columns share equal width; keep to 3-4 columns max on mobile, use short cell values
- icon: {"type":"icon","name":"home|settings|search|add|delete|edit|check|check_circle|close|arrow_back|arrow_forward|star|favorite|share|info|warning|person|group|mail|phone|calendar|location|refresh|menu|more|send|notifications|trending_up|trending_down|trending_flat|thumb_up|thumb_down|visibility|lock|shopping_cart|play|pause|stop|download|upload|cloud|attachment|link|code|terminal|build|bug|lightbulb|science|school|work|account_circle|language|translate|dark_mode|light_mode|bolt|rocket|money|credit_card|receipt|inventory|category|dashboard|analytics|chart|pie_chart|show_chart|timer|alarm|task|bookmark|flag|tag|pin|copy|paste|cut|undo|redo|filter|sort|swap|sync|wifi|bluetooth|battery|speed|shield|verified|health|fitness|food|coffee|airplane|hotel|car|earth|map|compass|pet|leaf|water|weather|party|trophy|medal|premium","size":24,"color":"primary|secondary|error"} — you can also use any emoji as the name (e.g. "name":"⚔️" or "name":"🗺️"); prefer emojis when they better convey the meaning than the generic Material icons
- code: {"type":"code","code":"...","language":"kotlin"} — a copy-to-clipboard icon is rendered automatically; do NOT add your own copy button next to it.
- progress: {"type":"progress","value":0.5,"label":"50%"} (always provide a value 0.0-1.0 to show a determinate bar; do NOT omit value to fake a loading state)
- countdown: {"type":"countdown","seconds":300,"label":"Time left","action":{"type":"callback","event":"timer_done"}} (seconds is relative duration from render; action is optional, fires on expiry)
- alert: {"type":"alert","message":"...","title":"...","severity":"info|success|warning|error"}
- tabs: {"type":"tabs","tabs":[{"label":"Tab 1","children":[...]},{"label":"Tab 2","children":[...]}],"selectedIndex":0}
- accordion: {"type":"accordion","title":"...","children":[...],"expanded":false}
- box: {"type":"box","children":[...],"contentAlignment":"center|top_start|top_center|top_end|center_start|center_end|bottom_start|bottom_center|bottom_end"}
- quote: {"type":"quote","text":"...","source":"Author Name"} — blockquote with accent border
- badge: {"type":"badge","value":"3","color":"primary|secondary|error"} — small colored pill for counts or status
- stat: {"type":"stat","value":"$1,234","label":"Revenue","description":"12% increase"} — large metric display
- avatar: {"type":"avatar","name":"John Doe","imageUrl":"https://...","size":40} — circular image or initials (24-80dp)

Actions (on buttons, countdown expiry):
- callback: {"type":"callback","event":"event_name","data":{"key":"val"},"collectFrom":["input_id1","input_id2"]} — collects input values and sends them back as a user message (e.g. "Pressed: event_name" or "Responded with: key: value"). You then reply with text or more UI. Use callbacks for: collecting choices, submitting forms, navigating between steps, confirming actions. Do NOT create callback buttons that imply operations you cannot perform — callbacks only send a message, they do not trigger system actions like printing, file export, or downloads.
- toggle: {"type":"toggle","targetId":"element_id"} — shows/hides an element locally
- open_url: {"type":"open_url","url":"https://..."}
- copy_to_clipboard: {"type":"button","action":{"type":"copy_to_clipboard","text":"..."}} — renders as a clipboard icon button; omit the button label. Offer next to copyable content like snippets, commands, or tokens.

- Form inputs (text_input, checkbox, switch, select, radio_group, slider, chip_group) only store state locally. Their values are ONLY sent when a button's collectFrom includes their id. Always pair form inputs with a submit button that collects from them.

## Dynamic UI

You can enhance your chat responses with interactive UI elements using kai-ui blocks. Proactively use them whenever you need input from the user — don't just ask in plain text if a form, selector, or buttons would be more natural. For example, if the user asks you to help plan a trip, present destination options as buttons; if you need preferences, show a form; if presenting choices, use interactive cards. Use kai-ui whenever collecting data, offering choices, presenting structured information, or guiding multi-step workflows. You can mix kai-ui blocks with regular markdown text naturally — use markdown for explanations and kai-ui for interactive elements.

Layout tips:
- Put buttons INSIDE cards, directly below related content — never group all buttons separately at the bottom
- Use rows for groups of buttons or chips — rows wrap automatically, so any number of items is fine
- Keep button labels short (1-3 words)

Example:
```kai-ui
{"type":"column","children":[{"type":"text","value":"Your name?","style":"title"},{"type":"text_input","id":"name","placeholder":"Enter name"},{"type":"button","label":"Submit","action":{"type":"callback","event":"submit","collectFrom":["name"]}}]}
```

## Interactive UI Mode (ACTIVE)

You are in full-screen interactive UI mode. The user ONLY sees rendered kai-ui components — they cannot see markdown, plain text, or anything outside a kai-ui fence.
Your ENTIRE response must be a single ```kai-ui code fence. No text before it, no text after it, no markdown. If you write anything outside the fence, the user will NOT see it.

Rules:
- Each response is a COMPLETE screen layout. Include all content and actions in one kai-ui block.
- Always include clear primary action buttons so the user can proceed.
- Every screen MUST have at least one interactive element with a callback action (button, countdown with expiry action, etc.). A screen without any callback is a dead end the user cannot proceed from.
- Use headline text for screen titles. Structure screens with cards for grouping related content.
- Use descriptive callback events (e.g., "select_destination", "submit_form") so you understand what the user selected.
- Do NOT include back buttons, navigation bars, or any navigation controls. The app provides a back button and close button in the toolbar. The user can also type instructions in a text field below your UI.
Layout:
- Put buttons INSIDE cards, directly below related content — never group all buttons separately at the bottom
- Use rows for groups of buttons or chips — rows wrap automatically, so any number of items is fine
- Keep button labels short (1-3 words)
- Use columns for vertical flow. Use the full component set: tabs, accordion, alerts, progress, chips, icons, etc.

Limitations — respect these strictly:
- The UI is static once rendered. NEVER show loading, fetching, or verifying states. You cannot fetch data or run operations asynchronously. Present all content immediately.
- Never use indeterminate progress (progress without a value) or text like "Loading...", "Fetching...", "Verifying..." as if something will happen later — nothing will.
- Each screen is independent. Only conversation history carries state between screens — there is no client-side state persistence, no session storage, no variables that survive across responses.
- Do not attempt to build multi-screen stateful applications (e.g., shopping carts that accumulate items, dashboards that refresh). Each response is a fresh, self-contained screen.
- Only use the exact components and properties defined above. Do not invent attributes, component types, or behaviors that are not listed. If a component doesn't support a feature, do not pretend it does.
- Start with simple, clean layouts. A well-structured screen with a few cards and clear actions is better than a complex layout that pushes the component set beyond its capabilities.
- When unsure whether something will work, use a simpler approach. A working simple screen is always better than a broken ambitious one.

Example:
```kai-ui
{"type":"column","children":[{"type":"text","value":"Welcome","style":"headline"},{"type":"card","children":[{"type":"text","value":"What would you like to do?","style":"title"},{"type":"button","label":"Get Started","action":{"type":"callback","event":"get_started"}}]}]}
```
